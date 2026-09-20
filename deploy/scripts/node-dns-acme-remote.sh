#!/usr/bin/env bash
set -euo pipefail
mode="${1:?auth or cleanup}"
[[ "$mode" == auth || "$mode" == cleanup ]] || exit 2
[[ "$CERTBOT_DOMAIN" == linux1.wayuwa.cn || "$CERTBOT_DOMAIN" == linux2.wayuwa.cn ]] || exit 2
[[ "$CERTBOT_VALIDATION" =~ ^[A-Za-z0-9_-]+$ ]] || exit 2
exec ssh -n -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 \
  -i /etc/wao-node-tls/ssh-key -o UserKnownHostsFile=/etc/wao-node-tls/known-hosts \
  wage@linux1.wayuwa.cn \
  "CERTBOT_DOMAIN='$CERTBOT_DOMAIN' CERTBOT_VALIDATION='$CERTBOT_VALIDATION' python3 /home/wage/.local/libexec/node-dns-acme-hook.py '$mode'"
