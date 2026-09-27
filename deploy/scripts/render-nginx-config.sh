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
SLAVE_PUBLIC_HOST="${SLAVE_PUBLIC_HOST:-linux1.wayuwa.cn}"
output_path="${WAO_NGINX_RENDER_OUTPUT:-${deploy_dir}/nginx/wao.wayuwa.cn.conf}"

resolve_ipv4() {
  sh "${script_dir}/resolve-gateway-ipv4.sh" "$1"
}

MASTER_GATEWAY_IPV4="${MASTER_GATEWAY_IPV4:-$(resolve_ipv4 "${MASTER_PUBLIC_HOST}")}"
SLAVE_GATEWAY_IPV4="${SLAVE_GATEWAY_IPV4:-$(resolve_ipv4 "${SLAVE_PUBLIC_HOST}")}"
SLAVE_GATEWAY_TUNNEL_IPV4="${SLAVE_GATEWAY_TUNNEL_IPV4:-127.0.0.1}"
MASTER_GATEWAY_STATE="${MASTER_GATEWAY_STATE:-}"
SLAVE_GATEWAY_STATE="${SLAVE_GATEWAY_STATE:-}"

for value in "${MASTER_GATEWAY_IPV4}" "${SLAVE_GATEWAY_IPV4}" "${SLAVE_GATEWAY_TUNNEL_IPV4}"; do
  case "${value}" in
    ''|*[!0-9.]*)
      echo "Unable to resolve a valid IPv4 Gateway address." >&2
      exit 1
      ;;
  esac
done
[ "${SLAVE_GATEWAY_IPV4}" != "${SLAVE_GATEWAY_TUNNEL_IPV4}" ] || {
  echo 'Linux1 Gateway primary and tunnel backup must be distinct.' >&2
  exit 1
}

for state in "${MASTER_GATEWAY_STATE}" "${SLAVE_GATEWAY_STATE}"; do
  case "${state}" in
    ''|' down') ;;
    *) echo 'Gateway state must be empty or " down".' >&2; exit 1 ;;
  esac
done

export MASTER_GATEWAY_IPV4 SLAVE_GATEWAY_IPV4 SLAVE_GATEWAY_TUNNEL_IPV4 \
  MASTER_GATEWAY_STATE SLAVE_GATEWAY_STATE

envsubst '${MASTER_GATEWAY_IPV4} ${SLAVE_GATEWAY_IPV4} ${SLAVE_GATEWAY_TUNNEL_IPV4} ${MASTER_GATEWAY_STATE} ${SLAVE_GATEWAY_STATE}' \
  <"${deploy_dir}/nginx/wao.wayuwa.cn.conf.template" \
  >"${output_path}"

echo "Rendered ${output_path} with IPv4 Gateway upstreams."
