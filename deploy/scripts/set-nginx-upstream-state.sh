#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo 'Run this script as root.' >&2
  exit 1
fi

node="${1:-}"
state="${2:-}"
case "${node}:${state}" in
  master:up|master:down|slave:up|slave:down) ;;
  *) echo 'Usage: set-nginx-upstream-state.sh <master|slave> <up|down>' >&2; exit 2 ;;
esac

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
runtime_dir='/run/wao-nginx-upstreams'
lock_file="${runtime_dir}/deployment.lock"
final_config='/etc/nginx/conf.d/wao.wayuwa.cn.conf'
pending_config="${runtime_dir}/wao.pending.conf"
previous_config="${runtime_dir}/wao.previous.conf"

install -d -m 0755 "${runtime_dir}"
touch "${lock_file}"
if [ "${state}" = down ]; then
  touch "${runtime_dir}/${node}.down"
else
  rm -f "${runtime_dir}/${node}.down"
fi

MASTER_GATEWAY_STATE=''
SLAVE_GATEWAY_STATE=''
[ -f "${runtime_dir}/master.down" ] && MASTER_GATEWAY_STATE=' down'
[ -f "${runtime_dir}/slave.down" ] && SLAVE_GATEWAY_STATE=' down'
export MASTER_GATEWAY_STATE SLAVE_GATEWAY_STATE

WAO_NGINX_RENDER_OUTPUT="${pending_config}" sh "${script_dir}/render-nginx-config.sh"
cp "${final_config}" "${previous_config}"
install -m 0644 "${pending_config}" "${final_config}"
if ! nginx -t; then
  mv "${previous_config}" "${final_config}"
  rm -f "${pending_config}"
  nginx -t
  exit 1
fi
rm -f "${pending_config}" "${previous_config}"
systemctl reload nginx

if [ ! -f "${runtime_dir}/master.down" ] && [ ! -f "${runtime_dir}/slave.down" ]; then
  rm -f "${lock_file}"
fi
echo "Gateway ${node} is ${state} in the public upstream."
