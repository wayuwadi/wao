#!/usr/bin/env bash
# Read-only MySQL source, one-way WebDAV publication of human-readable files.
set -Eeuo pipefail
umask 077
[[ "$(id -u)" -eq 0 ]] || { echo 'Readable export must run as root.' >&2; exit 1; }
exec 9>/run/lock/wao-readable-content.lock
flock -n 9 || { echo 'Another readable export is running.' >&2; exit 1; }
. "${WAO_PEER_BACKUP_CONFIG:-/etc/wao-backup/peer.env}"
: "${PROJECT_ROOT:?}"
set -a
. "$PROJECT_ROOT/deploy/.env"
set +a
: "${DB_ROOT_PASS:?}"
export MYSQL_PWD="$DB_ROOT_PASS"
unset DB_ROOT_PASS
exec python3 /usr/local/lib/wao-backup/export-readable-content.py "$@"
