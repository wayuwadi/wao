#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo 'Run this script as root.' >&2
  exit 1
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
deploy_dir="${project_root}/deploy"
final_config='/etc/nginx/conf.d/wao.wayuwa.cn.conf'
pending_config='/etc/nginx/conf.d/wao.pending.conf'
bootstrap_config='/etc/nginx/conf.d/wao.bootstrap.conf'
external_apps_dir='/etc/nginx/wao-external-apps.d'
deployment_drain_lock='/run/wao-nginx-upstreams/deployment.lock'

if [ -f "${deployment_drain_lock}" ]; then
  echo 'A rolling deployment is draining a Gateway; automatic DDNS activation is deferred.'
  exit 0
fi

MASTER_PUBLIC_HOST="${MASTER_PUBLIC_HOST:-linux2.wayuwa.cn}"
SLAVE_PUBLIC_HOST="${SLAVE_PUBLIC_HOST:-linux1.wayuwa.cn}"
export MASTER_PUBLIC_HOST SLAVE_PUBLIC_HOST

gateway_ready() {
  host="$1"
  getent ahostsv4 "${host}" >/dev/null 2>&1 &&
    curl -4 -kfsS --max-time 5 "https://${host}:9000/api/hello" | grep -q '"code":0'
}

if gateway_ready "${MASTER_PUBLIC_HOST}" || gateway_ready "${SLAVE_PUBLIC_HOST}"; then
  # 外部应用路由由独立仓库维护；目录必须先存在，Nginx 才能安全加载通用 include。
  install -d -m 0755 "${external_apps_dir}"
  sh "${script_dir}/render-nginx-config.sh"
  reload_required=false
  previous_config=''
  if [ ! -f "${final_config}" ] || ! cmp -s "${deploy_dir}/nginx/wao.wayuwa.cn.conf" "${final_config}"; then
    if [ -f "${final_config}" ]; then
      previous_config="/tmp/wao-nginx-previous.$$"
      cp "${final_config}" "${previous_config}"
    fi
    install -m 0644 "${deploy_dir}/nginx/wao.wayuwa.cn.conf" "${final_config}"
    if ! nginx -t; then
      if [ -n "${previous_config}" ]; then
        mv "${previous_config}" "${final_config}"
      else
        rm -f "${final_config}"
      fi
      echo 'The rendered Gateway configuration is invalid; keeping pending mode.' >&2
      exit 1
    fi
    if [ -n "${previous_config}" ]; then
      rm -f "${previous_config}"
    fi
    reload_required=true
  fi
  if [ -f "${pending_config}" ] || [ -f "${bootstrap_config}" ]; then
    reload_required=true
  fi
  rm -f "${pending_config}" "${bootstrap_config}"
  mode='gateway upstreams'
elif [ ! -f "${final_config}" ]; then
  install -m 0644 "${deploy_dir}/nginx/wao.pending.conf" "${pending_config}"
  rm -f "${bootstrap_config}"
  mode='pending API'
else
  echo 'No Gateway health endpoint is ready; keeping the last known working Nginx upstream configuration.'
  exit 0
fi

nginx -t
if [ "${reload_required:-true}" = true ]; then
  systemctl reload nginx
  echo "WAO Nginx activated in ${mode} mode."
else
  echo 'WAO Nginx IPv4 upstreams are unchanged; reload skipped.'
fi
