#!/usr/bin/env bash
set -euo pipefail
umask 077
set -a
. "${WAO_PEER_DEPLOY_ROOT:-$HOME/deploy/wao/deploy}/.env.secrets"
set +a
exec python3 "$(dirname "$0")/check-peer-backup.py" \
  "${WAO_PEER_SNAPSHOT_ROOT:-$HOME/backups/wao-peer}" \
  --state "${WAO_PEER_ALARM_STATE:-$HOME/.local/state/wao-peer-backup/alarm.json}" "$@"
