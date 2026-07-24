#!/bin/sh
set -eu

role="${1:?Usage: refresh-public-ip.sh <master|slave>}"
case "${role}" in
  master) compose_name='docker-compose-master.yml' ;;
  slave) compose_name='docker-compose-slave.yml' ;;
  *) echo "Unknown role: ${role}" >&2; exit 2 ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
deploy_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
env_file="${deploy_dir}/.env"
compose_file="${deploy_dir}/${compose_name}"
state_file="${deploy_dir}/.public-ip"

test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }
current_ip="$(curl -4fsS --max-time 10 https://api.ipify.org)"
case "${current_ip}" in
  *[!0-9.]*|'') echo "Invalid public IPv4 address: ${current_ip}" >&2; exit 1 ;;
esac

previous_ip=''
if [ -f "${state_file}" ]; then
  previous_ip="$(cat "${state_file}")"
fi

if [ "${current_ip}" = "${previous_ip}" ]; then
  exit 0
fi

echo "Public IPv4 changed from ${previous_ip:-unknown} to ${current_ip}; refreshing Nacos registrations."
NODE_PUBLIC_IP="${current_ip}" docker compose --env-file "${env_file}" -f "${compose_file}" up -d --no-deps --force-recreate --wait wao-server wao-gateway
printf '%s\n' "${current_ip}" >"${state_file}"
