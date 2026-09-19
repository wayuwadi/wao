#!/usr/bin/env bash
set -euo pipefail
# 以 Jenkins 用户运行。只为客户端使用的官方 443 主机设置别名，不更改其他 SSH 目标。
install -d -m 0700 "$HOME/.ssh"
cat >"$HOME/.ssh/wao-client-github.conf" <<'EOF'
Host ssh.github.com
    HostKeyAlias github.com
    StrictHostKeyChecking yes
    ConnectTimeout 20
    ServerAliveInterval 15
    ServerAliveCountMax 3
EOF
chmod 0600 "$HOME/.ssh/wao-client-github.conf"
config="$HOME/.ssh/config"
include='Include ~/.ssh/wao-client-github.conf'
if ! grep -qxF "$include" "$config" 2>/dev/null; then
  temporary=$(mktemp "$HOME/.ssh/config.XXXXXX")
  printf '%s\n' "$include" >"$temporary"
  if [ -f "$config" ]; then cat "$config" >>"$temporary"; fi
  chmod 0600 "$temporary"
  mv "$temporary" "$config"
fi
