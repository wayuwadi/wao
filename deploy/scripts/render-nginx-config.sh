#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
deploy_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
if [ -f "${deploy_dir}/.env" ]; then
  set -a
  . "${deploy_dir}/.env"
  set +a
fi

MASTER_PUBLIC_HOST="${MASTER_PUBLIC_HOST:-linux2.wayuwa.cn}"
output_path="${WAO_NGINX_RENDER_OUTPUT:-${deploy_dir}/nginx/wao.wayuwa.cn.conf}"

resolve_ipv4() {
  sh "${script_dir}/resolve-gateway-ipv4.sh" "$1"
}

MASTER_GATEWAY_IPV4="${MASTER_GATEWAY_IPV4:-$(resolve_ipv4 "${MASTER_PUBLIC_HOST}")}"
WAO_GATEWAY_SERVERS="    server ${MASTER_GATEWAY_IPV4}:9000 max_fails=3 fail_timeout=10s;"

for value in "${MASTER_GATEWAY_IPV4}"; do
  case "${value}" in
    ''|*[!0-9.]*)
      echo "Unable to resolve a valid IPv4 Gateway address." >&2
      exit 1
      ;;
  esac
done
export MASTER_GATEWAY_IPV4 WAO_GATEWAY_SERVERS

envsubst '${MASTER_GATEWAY_IPV4} ${WAO_GATEWAY_SERVERS}' \
  <"${deploy_dir}/nginx/wao.wayuwa.cn.conf.template" \
  >"${output_path}"

echo "Rendered ${output_path} with IPv4 Gateway upstreams."
