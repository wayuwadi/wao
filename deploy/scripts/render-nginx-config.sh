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
topology="${WAO_DEPLOY_TOPOLOGY:-$(cat /etc/wao-deploy-topology 2>/dev/null || true)}"
case "${topology:-dual}" in
  dual|linux2-only) ;;
  *) echo "Unsupported WAO deployment topology: $topology" >&2; exit 1 ;;
esac
output_path="${WAO_NGINX_RENDER_OUTPUT:-${deploy_dir}/nginx/wao.wayuwa.cn.conf}"

resolve_ipv4() {
  sh "${script_dir}/resolve-gateway-ipv4.sh" "$1"
}

MASTER_GATEWAY_IPV4="${MASTER_GATEWAY_IPV4:-$(resolve_ipv4 "${MASTER_PUBLIC_HOST}")}"
MASTER_GATEWAY_STATE="${MASTER_GATEWAY_STATE:-}"
SLAVE_GATEWAY_STATE="${SLAVE_GATEWAY_STATE:-}"

if [ "${topology:-dual}" = linux2-only ]; then
  SLAVE_GATEWAY_IPV4=''
  SLAVE_GATEWAY_TUNNEL_IPV4=''
  WAO_GATEWAY_SERVERS="    server ${MASTER_GATEWAY_IPV4}:9000${MASTER_GATEWAY_STATE} max_fails=3 fail_timeout=10s;"
else
  SLAVE_GATEWAY_IPV4="${SLAVE_GATEWAY_IPV4:-$(resolve_ipv4 "${SLAVE_PUBLIC_HOST}")}"
  SLAVE_GATEWAY_TUNNEL_IPV4="${SLAVE_GATEWAY_TUNNEL_IPV4:-127.0.0.1}"
  WAO_GATEWAY_SERVERS="    server ${MASTER_GATEWAY_IPV4}:9000 backup${MASTER_GATEWAY_STATE} max_fails=3 fail_timeout=10s;
    server ${SLAVE_GATEWAY_IPV4}:9000${SLAVE_GATEWAY_STATE} max_fails=3 fail_timeout=10s;
    server ${SLAVE_GATEWAY_TUNNEL_IPV4}:9000 backup${SLAVE_GATEWAY_STATE} max_fails=3 fail_timeout=10s;"
  [ "${SLAVE_GATEWAY_IPV4}" != "${SLAVE_GATEWAY_TUNNEL_IPV4}" ] || {
    echo 'Linux1 Gateway primary and tunnel backup must be distinct.' >&2
    exit 1
  }
fi

for value in "${MASTER_GATEWAY_IPV4}"; do
  case "${value}" in
    ''|*[!0-9.]*)
      echo "Unable to resolve a valid IPv4 Gateway address." >&2
      exit 1
      ;;
  esac
done
if [ "${topology:-dual}" = dual ]; then
  for value in "${SLAVE_GATEWAY_IPV4}" "${SLAVE_GATEWAY_TUNNEL_IPV4}"; do
    case "${value}" in ''|*[!0-9.]*) echo 'Invalid Linux1 Gateway IPv4.' >&2; exit 1 ;; esac
  done
fi

for state in "${MASTER_GATEWAY_STATE}" "${SLAVE_GATEWAY_STATE}"; do
  case "${state}" in
    ''|' down') ;;
    *) echo 'Gateway state must be empty or " down".' >&2; exit 1 ;;
  esac
done

export MASTER_GATEWAY_IPV4 SLAVE_GATEWAY_IPV4 SLAVE_GATEWAY_TUNNEL_IPV4 \
  MASTER_GATEWAY_STATE SLAVE_GATEWAY_STATE WAO_GATEWAY_SERVERS

envsubst '${MASTER_GATEWAY_IPV4} ${SLAVE_GATEWAY_IPV4} ${SLAVE_GATEWAY_TUNNEL_IPV4} ${MASTER_GATEWAY_STATE} ${SLAVE_GATEWAY_STATE} ${WAO_GATEWAY_SERVERS}' \
  <"${deploy_dir}/nginx/wao.wayuwa.cn.conf.template" \
  >"${output_path}"

echo "Rendered ${output_path} with IPv4 Gateway upstreams."
