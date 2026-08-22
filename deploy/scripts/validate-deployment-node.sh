#!/bin/sh
set -eu

role="${1:?Usage: validate-deployment-node.sh <master|slave> EXPECTED_IMAGE_TAG}"
expected_tag="${2:?Usage: validate-deployment-node.sh <master|slave> EXPECTED_IMAGE_TAG}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
env_file="${project_root}/deploy/.env"

case "${role}" in
  master) compose_file="${project_root}/deploy/docker-compose-master.yml" ;;
  slave) compose_file="${project_root}/deploy/docker-compose-slave.yml" ;;
  *) echo "Unknown role: ${role}" >&2; exit 2 ;;
esac

test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }
case "${expected_tag}" in
  *[!A-Za-z0-9_.-]*|'') echo 'Invalid expected image tag.' >&2; exit 2 ;;
esac

if [ -f "${project_root}/deploy/.public-ip" ]; then
  NODE_PUBLIC_IP="$(sed -n '1p' "${project_root}/deploy/.public-ip" | tr -d '\r\n')"
  export NODE_PUBLIC_IP
fi

run_docker() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  elif sudo -n docker info >/dev/null 2>&1; then
    sudo -n env "NODE_PUBLIC_IP=${NODE_PUBLIC_IP:-}" docker "$@"
  else
    echo 'Docker is unavailable to the current user.' >&2
    return 1
  fi
}

compose() {
  if [ -f "${project_root}/deploy/.env.secrets" ]; then
    run_docker compose --env-file "${env_file}" --env-file "${project_root}/deploy/.env.secrets" \
      -f "${compose_file}" "$@"
  else
    run_docker compose --env-file "${env_file}" -f "${compose_file}" "$@"
  fi
}

for service in wao-server wao-agent-server wao-gateway; do
  container_id="$(compose ps -q "${service}")"
  test -n "${container_id}" || { echo "Missing container for ${service}." >&2; exit 1; }
  running_image="$(run_docker inspect --format '{{.Config.Image}}' "${container_id}")"
  expected_image="${service}:${expected_tag}"
  test "${running_image}" = "${expected_image}" || {
    echo "${service} runs ${running_image}; expected ${expected_image}." >&2
    exit 1
  }
  health="$(run_docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
  test "${health}" = healthy || { echo "${service} is ${health}." >&2; exit 1; }
  printf '%s_image=%s health=%s\n' "${service}" "${running_image}" "${health}"
done

gateway_id="$(compose ps -q wao-gateway)"
hello="$(run_docker exec "${gateway_id}" curl -kfsS https://127.0.0.1:9000/api/hello)"
printf '%s' "${hello}" | grep -q '"code":0'
printf 'gateway_api=%s\n' "${hello}"

if [ "${role}" = slave ]; then
  sh "${project_root}/deploy/scripts/validate-slave.sh"
fi
