#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
deploy_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
set -a
. "${deploy_dir}/.env"
set +a

: "${MASTER_TAILSCALE_IP:?MASTER_TAILSCALE_IP is required}"
: "${SLAVE_TAILSCALE_IP:?SLAVE_TAILSCALE_IP is required}"

envsubst '${MASTER_TAILSCALE_IP} ${SLAVE_TAILSCALE_IP}' \
  <"${deploy_dir}/nginx/wao.wayuwa.cn.conf.template" \
  >"${deploy_dir}/nginx/wao.wayuwa.cn.conf"

echo "Rendered ${deploy_dir}/nginx/wao.wayuwa.cn.conf"
