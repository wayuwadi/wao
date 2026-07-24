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
  *) echo "Unknown role: ${role}" >&2; exit 2 ;;
esac

test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }

NODE_PUBLIC_IP="${NODE_PUBLIC_IP:-$(curl -4fsS --max-time 10 https://api.ipify.org)}"
case "${NODE_PUBLIC_IP}" in
  *[!0-9.]*|'') echo "Invalid public IPv4 address: ${NODE_PUBLIC_IP}" >&2; exit 1 ;;
esac
export NODE_PUBLIC_IP

docker compose --env-file "${env_file}" -f "${compose_file}" config >/dev/null

for service in wao-server wao-gateway; do
  container_id="$(docker compose --env-file "${env_file}" -f "${compose_file}" ps -q "${service}")"
  if [ -n "${container_id}" ]; then
    image_id="$(docker inspect --format '{{.Image}}' "${container_id}")"
    docker tag "${image_id}" "${service}:rollback"
  fi
done
docker compose --env-file "${env_file}" -f "${compose_file}" build wao-server wao-gateway

if ! docker compose --env-file "${env_file}" -f "${compose_file}" up -d --wait; then
  if docker image inspect wao-server:rollback wao-gateway:rollback >/dev/null 2>&1; then
    echo 'Deployment failed; restoring the previously running application images.' >&2
    WAO_IMAGE_TAG=rollback docker compose --env-file "${env_file}" -f "${compose_file}" up -d --no-build --no-deps --wait wao-server wao-gateway
  fi
  exit 1
fi
docker compose --env-file "${env_file}" -f "${compose_file}" ps

printf '%s\n' "${NODE_PUBLIC_IP}" >"${deploy_dir}/.public-ip"

if [ "${role}" = "master" ] && command -v crontab >/dev/null 2>&1; then
  cron_marker='# WAO public IP refresh'
  existing_crontab="$(crontab -l 2>/dev/null || true)"
  {
    printf '%s\n' "${existing_crontab}" | grep -vF "${cron_marker}" || true
    printf '%s\n' "*/5 * * * * cd '${project_root}' && sh deploy/scripts/refresh-public-ip.sh master ${cron_marker}"
  } | crontab -
fi
