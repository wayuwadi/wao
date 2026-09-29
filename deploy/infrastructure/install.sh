#!/bin/sh
# Run as root on Linux2. No dependencies beyond its existing Python/OpenSSH/Docker.
set -eu
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
install -d -m 0755 /opt/wao-infrastructure
install -d -m 0700 /etc/wao-infrastructure /var/lib/wao-infrastructure
install -m 0644 "$source_dir/probe.py" "$source_dir/server.py" /opt/wao-infrastructure/
python3 - <<'PY'
import json
from pathlib import Path
base=['sudo','-n','-u','jenkins','ssh','-o','BatchMode=yes','-o','ConnectTimeout=8','-o','StrictHostKeyChecking=yes','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2']
cloud=base+['-i','/etc/jenkins/secrets/cloud-ed25519','-o','UserKnownHostsFile=/etc/jenkins/secrets/cloud-known-hosts','root@8.136.152.131','python3 -']
nodes=[dict(id='linux2',name='Linux2',role='WAO 应用与数据库节点',address='linux2.wayuwa.cn',command=['python3','-']),dict(id='cloud',name='云服务器',role='HTTPS 入口 · 反向代理',address='8.136.152.131',command=cloud)]
p=Path('/etc/wao-infrastructure/nodes.json');p.write_text(json.dumps(nodes,ensure_ascii=False));p.chmod(0o600)
PY
install -m 0644 "$source_dir/wao-infrastructure.service" "$source_dir/wao-infrastructure-tunnel.service" /etc/systemd/system/
systemctl daemon-reload
for retired_unit in \
  wao-public-certificate-sync.timer wao-public-certificate-sync.service \
  wao-linux2-mesh-endpoint.timer wao-linux2-mesh-endpoint.service \
  wao-private-mesh-endpoint.timer wao-private-mesh-endpoint.service \
  wao-private-mesh.service wao-mysql-primary-tunnel.service; do
  if systemctl cat "$retired_unit" >/dev/null 2>&1; then
    systemctl disable --now "$retired_unit"
  fi
done
systemctl enable wao-infrastructure.service wao-infrastructure-tunnel.service
systemctl restart wao-infrastructure.service
systemctl start wao-infrastructure-tunnel.service
