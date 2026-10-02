#!/bin/sh
# 停用已知 WAO 执行服务，保留配置、令牌、会话与 worktree。
set -eu
if systemctl cat wao-ai-runner.service >/dev/null 2>&1; then
  systemctl disable --now wao-ai-runner.service
fi
for owner in wadi jenkins; do
  uid=$(id -u "$owner" 2>/dev/null) || continue
  test -d "/run/user/$uid" || continue
  if runuser -u "$owner" -- env XDG_RUNTIME_DIR="/run/user/$uid" systemctl --user cat wao-ai-runner.service >/dev/null 2>&1; then
    runuser -u "$owner" -- env XDG_RUNTIME_DIR="/run/user/$uid" systemctl --user disable --now wao-ai-runner.service
  fi
done
