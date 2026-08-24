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
runtime_dir="${WAO_NGINX_RUNTIME_DIR:-/run/wao-nginx-upstreams}"
lock_file="${runtime_dir}/deployment.lock"
final_config="${WAO_NGINX_FINAL_CONFIG:-/etc/nginx/conf.d/wao.wayuwa.cn.conf}"
pending_config="${runtime_dir}/wao.pending.conf"
previous_config="${runtime_dir}/wao.previous.conf"
worker_drain_seconds="${WAO_NGINX_WORKER_DRAIN_SECONDS:-10}"
ps_command="${WAO_NGINX_PS_COMMAND:-ps}"
kill_command="${WAO_NGINX_KILL_COMMAND:-/bin/kill}"

case "${worker_drain_seconds}" in
  ''|*[!0-9]*)
    echo 'WAO_NGINX_WORKER_DRAIN_SECONDS must be a non-negative integer.' >&2
    exit 2
    ;;
esac

nginx_master_pid() {
  cat /run/nginx.pid 2>/dev/null || true
}

nginx_worker_pids() {
  master_pid="$1"
  [ -n "${master_pid}" ] || return 0
  "${ps_command}" -eo pid=,ppid=,args= | awk -v master="${master_pid}" '
    $2 == master && $0 ~ /nginx: worker process/ { print $1 }
  '
}

worker_still_belongs_to_master() {
  worker_pid="$1"
  master_pid="$2"
  current_parent="$("${ps_command}" -o ppid= -p "${worker_pid}" 2>/dev/null | tr -d ' ' || true)"
  [ "${current_parent}" = "${master_pid}" ]
}

drain_reloaded_workers() {
  master_pid="$1"
  old_workers="$2"
  [ -n "${master_pid}" ] && [ -n "${old_workers}" ] || return 0

  elapsed=0
  while [ "${elapsed}" -lt "${worker_drain_seconds}" ]; do
    remaining=''
    for worker_pid in ${old_workers}; do
      if worker_still_belongs_to_master "${worker_pid}" "${master_pid}"; then
        remaining="${remaining} ${worker_pid}"
      fi
    done
    [ -n "${remaining}" ] || return 0
    sleep 1
    elapsed=$((elapsed + 1))
  done

  # HTTP/2/WebSocket clients can keep an old worker alive indefinitely after a
  # graceful reload. Stop only the worker PIDs captured before this reload so
  # new workers, the Nginx master and the remaining healthy upstream stay up.
  for worker_pid in ${old_workers}; do
    if worker_still_belongs_to_master "${worker_pid}" "${master_pid}"; then
      "${kill_command}" -TERM "${worker_pid}"
    fi
  done

  elapsed=0
  while [ "${elapsed}" -lt 5 ]; do
    remaining=''
    for worker_pid in ${old_workers}; do
      if worker_still_belongs_to_master "${worker_pid}" "${master_pid}"; then
        remaining="${remaining} ${worker_pid}"
      fi
    done
    [ -n "${remaining}" ] || return 0
    sleep 1
    elapsed=$((elapsed + 1))
  done

  echo "Old Nginx workers did not exit after reload:${remaining}" >&2
  return 1
}

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
master_pid="$(nginx_master_pid)"
old_workers="$(nginx_worker_pids "${master_pid}")"
systemctl reload nginx
drain_reloaded_workers "${master_pid}" "${old_workers}"

if [ ! -f "${runtime_dir}/master.down" ] && [ ! -f "${runtime_dir}/slave.down" ]; then
  rm -f "${lock_file}"
fi
echo "Gateway ${node} is ${state} in the public upstream."
