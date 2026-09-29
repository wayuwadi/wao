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
if [[ -f "$PROJECT_ROOT/deploy/.env.secrets" ]]; then
  . "$PROJECT_ROOT/deploy/.env.secrets"
fi
set +a
: "${DB_ROOT_PASS:?}" "${DB_USER:?}" "${DB_PASS:?}"
# The retired peer is not a valid source. Read from the local Linux2 writer only.
[[ "${WAO_MYSQL_PRIMARY:-}" == linux2 ]] || {
  echo 'Readable export requires the Linux2 WAO writer.' >&2
  exit 1
}
export WAO_EXPORT_LOCAL_UUID
WAO_EXPORT_LOCAL_UUID="$(MYSQL_PWD="$DB_ROOT_PASS" mysql -uroot -h127.0.0.1 \
  -P "${MYSQL_PORT:-3306}" -N -e 'SELECT @@server_uuid')"
[[ -n "$WAO_EXPORT_LOCAL_UUID" ]] || { echo 'Could not identify local MySQL.' >&2; exit 1; }
export WAO_EXPORT_PRIMARY=linux2
export WAO_EXPORT_MYSQL_HOST=127.0.0.1
export WAO_EXPORT_MYSQL_PORT="${MYSQL_PORT:-3306}"
export WAO_EXPORT_MYSQL_USER="$DB_USER"
export MYSQL_PWD="$DB_PASS"
unset DB_ROOT_PASS DB_PASS
python_bin=/usr/local/lib/wao-backup/.venv/bin/python
[[ -x "$python_bin" ]] || python_bin=python3
exec "$python_bin" /usr/local/lib/wao-backup/export-readable-content.py "$@"
