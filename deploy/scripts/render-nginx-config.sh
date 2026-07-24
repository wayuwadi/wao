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
export MASTER_PUBLIC_HOST SLAVE_PUBLIC_HOST

envsubst '${MASTER_PUBLIC_HOST} ${SLAVE_PUBLIC_HOST}' \
  <"${deploy_dir}/nginx/wao.wayuwa.cn.conf.template" \
  >"${deploy_dir}/nginx/wao.wayuwa.cn.conf"

echo "Rendered ${deploy_dir}/nginx/wao.wayuwa.cn.conf"
