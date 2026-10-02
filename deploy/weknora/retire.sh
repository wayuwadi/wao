#!/bin/sh
# 仅停止 WAO 自有 WeKnora 安装，保留数据库、向量、文件卷与备份。
set -eu
for unit in wao-weknora-tunnel.service wao-weknora-backup.timer; do
  if systemctl cat "$unit" >/dev/null 2>&1; then
    systemctl disable --now "$unit"
  fi
done
for container in $(docker ps -aq --filter label=com.docker.compose.project=wao-weknora); do
  workdir=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$container")
  case "$workdir" in
    /opt/wao-weknora|/opt/wao-weknora/*)
      docker update --restart=no "$container" >/dev/null
      docker stop "$container" >/dev/null
      ;;
  esac
done
