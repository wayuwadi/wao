#!/bin/sh
set -eu

run_docker() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  elif sudo -n docker info >/dev/null 2>&1; then
    sudo -n docker "$@"
  else
    echo '当前用户无法访问 Docker，且没有配置免密 sudo docker。' >&2
    return 1
  fi
}

server_id="$(run_docker ps --filter label=com.docker.compose.service=wao-server -q | head -n1)"
test -n "${server_id}" || {
  echo 'Linux 主节点没有运行 wao-server 容器。' >&2
  exit 1
}

proxy_id="$(run_docker ps --filter label=com.docker.compose.service=codex-egress-proxy -q | head -n1)"
test -n "${proxy_id}" || {
  echo 'Linux 主节点没有运行 codex-egress-proxy 容器。' >&2
  exit 1
}
if ! run_docker exec "${proxy_id}" curl --version >/dev/null 2>&1; then
  echo 'Codex 出站代理仍在运行不含 curl 的旧镜像，请重新构建并强制重建该容器。' >&2
  exit 1
fi

probe_chatgpt() {
  container_id="$1"
  shift
  run_docker exec "${container_id}" curl --silent --show-error --connect-timeout 10 --max-time 30 \
    --output /dev/null --write-out '%{http_code}' "$@" \
    https://chatgpt.com/backend-api/wham/usage
}

direct_status="$(probe_chatgpt "${proxy_id}" --proxy http://127.0.0.1:18888)" || {
  echo 'Codex 出站代理容器无法通过无桌面 VPN 服务访问 ChatGPT。' >&2
  run_docker logs --tail 50 "${proxy_id}" >&2
  exit 1
}
case "${direct_status}" in
  200|401) ;;
  *)
    printf 'Codex 出站代理容器的真实 ChatGPT 探针失败：HTTP %s\n' "${direct_status}" >&2
    run_docker logs --tail 50 "${proxy_id}" >&2
    exit 1
    ;;
esac

proxy_url="$(run_docker exec "${server_id}" sh -c 'printf "%s" "$WAO_AI_CODEX_PROXY_URL"')"
test -n "${proxy_url}" || {
  echo 'Linux 主节点 wao-server 没有配置 Codex 出站代理。' >&2
  exit 1
}

status="$(probe_chatgpt "${server_id}" --proxy "${proxy_url}")" || {
  printf 'wao-server 无法连接 Codex 内部代理：%s\n' "${proxy_url}" >&2
  run_docker logs --tail 50 "${proxy_id}" >&2
  exit 1
}

case "${status}" in
  200|401) ;;
  *)
    printf 'Linux 主节点 wao-server 经内部代理执行 Codex 出站探针失败：HTTP %s\n' "${status}" >&2
    run_docker logs --tail 50 "${proxy_id}" >&2
    exit 1
    ;;
esac

echo 'Linux 主节点 wao-server 经无桌面 VPN 服务执行的 Codex 出站探针通过。'
