#!/usr/bin/env bash
# Installed as a Certbot deploy hook on Linux2. No keys enter the repository.
set -euo pipefail
if [[ "${RENEWED_LINEAGE:-}" == /etc/letsencrypt/live/wao-linux2-public ]]; then
  exec /etc/wao-node-tls/deploy-local-node-certificate.sh
fi
exit 0
