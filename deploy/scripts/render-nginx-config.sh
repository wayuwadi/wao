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
SLAVE_PUBLIC_HOST="${SLAVE_PUBLIC_HOST:-windows1.wayuwa.cn}"
output_path="${WAO_NGINX_RENDER_OUTPUT:-${deploy_dir}/nginx/wao.wayuwa.cn.conf}"

resolve_ipv4() {
  host="$1"
  getent ahostsv4 "${host}" | awk '$2 == "STREAM" { print $1; exit }'
}

MASTER_GATEWAY_IPV4="${MASTER_GATEWAY_IPV4:-$(resolve_ipv4 "${MASTER_PUBLIC_HOST}")}"
SLAVE_GATEWAY_IPV4="${SLAVE_GATEWAY_IPV4:-$(resolve_ipv4 "${SLAVE_PUBLIC_HOST}")}"

for value in "${MASTER_GATEWAY_IPV4}" "${SLAVE_GATEWAY_IPV4}"; do
  case "${value}" in
    ''|*[!0-9.]*)
      echo "Unable to resolve a valid IPv4 Gateway address." >&2
      exit 1
      ;;
  esac
done

export MASTER_GATEWAY_IPV4 SLAVE_GATEWAY_IPV4

envsubst '${MASTER_GATEWAY_IPV4} ${SLAVE_GATEWAY_IPV4}' \
  <"${deploy_dir}/nginx/wao.wayuwa.cn.conf.template" \
  >"${output_path}"

echo "Rendered ${output_path} with IPv4 Gateway upstreams."
