#!/bin/sh
set -eu

proxy_url="${WAO_CODEX_HEALTH_PROXY_URL:-http://127.0.0.1:18888}"
status="$(curl --silent --show-error --connect-timeout 5 --max-time 15 \
  --output /dev/null --write-out '%{http_code}' \
  --proxy "${proxy_url}" \
  https://chatgpt.com/backend-api/wham/usage)"

case "${status}" in
  200|401) exit 0 ;;
  *)
    echo "Codex 出站真实链路探针失败：HTTP ${status}" >&2
    exit 1
    ;;
esac
