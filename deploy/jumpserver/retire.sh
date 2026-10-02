#!/bin/sh
# 仅停用 WAO 私有 JumpServer 安装，不删除容器、数据、密钥或审计记录。
set -eu
state_root=/opt/wao-jumpserver
if systemctl cat wao-jumpserver-tunnel.service >/dev/null 2>&1; then
  systemctl disable --now wao-jumpserver-tunnel.service
fi
for container in $(docker ps -aq --filter label=com.docker.compose.project.working_dir); do
  workdir=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$container")
  case "$workdir" in
    "$state_root"/installer-v4.10.19|"$state_root"/installer-v4.10.19/*)
      docker update --restart=no "$container" >/dev/null
      docker stop "$container" >/dev/null
      ;;
  esac
done
