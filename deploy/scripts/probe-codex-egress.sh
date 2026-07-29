#!/bin/sh
set -eu

server_id="$(docker ps --filter label=com.docker.compose.service=wao-server -q | head -n1)"
test -n "${server_id}" || {
  echo 'Linux 主节点没有运行 wao-server 容器。' >&2
  exit 1
}

status="$(docker exec "${server_id}" curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request POST https://auth.openai.com/api/accounts/deviceauth/usercode \
  --header 'Content-Type: application/json' \
  --data '{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}')"

if [ "${status}" != '200' ]; then
  printf 'Linux 主节点 wao-server 的 Codex 出站探针失败：HTTP %s\n' "${status}" >&2
  exit 1
fi

echo 'Linux 主节点 wao-server 的 Codex VPN 出站探针通过。'
