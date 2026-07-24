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

set -a
. "${env_file}"
set +a

case "${role}" in
  master) public_host="${MASTER_PUBLIC_HOST:-linux2.wayuwa.cn}" ;;
  slave) public_host="${SLAVE_PUBLIC_HOST:-windows1.wayuwa.cn}" ;;
esac

current_ip="$(getent ahostsv4 "${public_host}" 2>/dev/null | awk '$2 == "STREAM" { print $1; exit }')"
if [ -z "${current_ip}" ]; then
  current_ip="$(curl -4fsS --max-time 10 https://api.ipify.org || curl -4fsS --max-time 10 https://4.ipw.cn)"
fi
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
if [ "${role}" = "master" ] && [ "${MANAGE_MASTER_INFRA:-true}" = "true" ]; then
  profile_args='--profile managed-infra'
else
  profile_args=''
fi

if docker info >/dev/null 2>&1; then
  # shellcheck disable=SC2086
  NODE_PUBLIC_IP="${current_ip}" docker compose ${profile_args} --env-file "${env_file}" -f "${compose_file}" up -d --no-deps --force-recreate --wait wao-server wao-gateway
elif sudo -n docker info >/dev/null 2>&1; then
  # shellcheck disable=SC2086
  sudo -n env "NODE_PUBLIC_IP=${current_ip}" docker compose ${profile_args} --env-file "${env_file}" -f "${compose_file}" up -d --no-deps --force-recreate --wait wao-server wao-gateway
else
  echo 'Docker is unavailable to the current user and passwordless sudo is not configured.' >&2
  exit 1
fi
printf '%s\n' "${current_ip}" >"${state_file}"
