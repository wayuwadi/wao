#!/bin/sh
set -eu

role="${1:-master}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
deploy_dir="${project_root}/deploy"
env_file="${deploy_dir}/.env"

case "${role}" in
  master) compose_file="${deploy_dir}/docker-compose-master.yml" ;;
  slave) compose_file="${deploy_dir}/docker-compose-slave.yml" ;;
  cloud-sentinel) compose_file="${deploy_dir}/docker-compose-cloud-sentinel.yml" ;;
  *) echo "Unknown role: ${role}" >&2; exit 2 ;;
esac

test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }
docker compose --env-file "${env_file}" -f "${compose_file}" config >/dev/null

if [ "${role}" = "master" ] || [ "${role}" = "slave" ]; then
  for service in wao-server wao-gateway; do
    container_id="$(docker compose --env-file "${env_file}" -f "${compose_file}" ps -q "${service}")"
    if [ -n "${container_id}" ]; then
      image_id="$(docker inspect --format '{{.Image}}' "${container_id}")"
      docker tag "${image_id}" "${service}:rollback"
    fi
  done
  docker compose --env-file "${env_file}" -f "${compose_file}" build wao-server wao-gateway
fi

if ! docker compose --env-file "${env_file}" -f "${compose_file}" up -d --wait; then
  if [ "${role}" = "master" ] || [ "${role}" = "slave" ]; then
    if docker image inspect wao-server:rollback wao-gateway:rollback >/dev/null 2>&1; then
      echo 'Deployment failed; restoring the previously running application images.' >&2
      WAO_IMAGE_TAG=rollback docker compose --env-file "${env_file}" -f "${compose_file}" up -d --no-build --no-deps --wait wao-server wao-gateway
    fi
  fi
  exit 1
fi
docker compose --env-file "${env_file}" -f "${compose_file}" ps
