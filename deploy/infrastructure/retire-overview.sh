#!/bin/sh
# 一次性退役基础设施清单与资源历史采集器；不触及发布健康监控和证书同步。
set -eu

for unit in wao-infrastructure.service wao-infrastructure-tunnel.service; do
  if systemctl cat "$unit" >/dev/null 2>&1; then
    systemctl disable --now "$unit"
  fi
done

rm -f /etc/systemd/system/wao-infrastructure.service \
  /etc/systemd/system/wao-infrastructure-tunnel.service
systemctl daemon-reload
rm -rf /opt/wao-infrastructure /etc/wao-infrastructure /var/lib/wao-infrastructure
