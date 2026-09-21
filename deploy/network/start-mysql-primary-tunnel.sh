#!/usr/bin/env bash
# Linux2-only transport to the already selected writer; never promotes a database.
set -euo pipefail
config="${WAO_MYSQL_TUNNEL_CONFIG:-/etc/wao-mysql-recovery/tunnel.env}"
[[ -f "$config" ]] || { echo 'Missing private MySQL tunnel configuration' >&2; exit 1; }
. "$config"
: "${MYSQL_PRIMARY_SSH_HOST:?}" "${SSH_KEY:?}" "${KNOWN_HOSTS:?}"
[[ "$MYSQL_PRIMARY_SSH_HOST" =~ ^[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+$ ]] || exit 2
[[ -f "$SSH_KEY" && -f "$KNOWN_HOSTS" ]] || exit 2
exec ssh -N -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=10 -o ServerAliveCountMax=3 \
  -o ConnectTimeout=10 -i "$SSH_KEY" -o "UserKnownHostsFile=$KNOWN_HOSTS" \
  -L 127.0.0.1:13306:127.0.0.1:3306 \
  -L 10.66.0.2:13306:127.0.0.1:3306 "$MYSQL_PRIMARY_SSH_HOST"
