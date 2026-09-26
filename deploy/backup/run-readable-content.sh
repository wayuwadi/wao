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
: "${DB_ROOT_PASS:?}" "${DB_USER:?}" "${DB_PASS:?}" "${WAO_MYSQL_PRIMARY:?}"
# Linux2's native instance stays writable for three other databases even when
# its WAO replica is stale. Identify it before selecting the actual WAO writer.
export WAO_EXPORT_LOCAL_UUID
WAO_EXPORT_LOCAL_UUID="$(MYSQL_PWD="$DB_ROOT_PASS" mysql -uroot -h127.0.0.1 \
  -P "${MYSQL_PORT:-3306}" -N -e 'SELECT @@server_uuid')"
[[ -n "$WAO_EXPORT_LOCAL_UUID" ]] || { echo 'Could not identify local MySQL.' >&2; exit 1; }
export WAO_EXPORT_PRIMARY="$WAO_MYSQL_PRIMARY"
case "$WAO_MYSQL_PRIMARY" in
  linux1)
    : "${LINUX2_MYSQL_WRITER_HOST:?}" "${LINUX2_MYSQL_WRITER_PORT:?}"
    export WAO_EXPORT_MYSQL_HOST="$LINUX2_MYSQL_WRITER_HOST"
    export WAO_EXPORT_MYSQL_PORT="$LINUX2_MYSQL_WRITER_PORT"
    ;;
  linux2)
    export WAO_EXPORT_MYSQL_HOST=127.0.0.1
    export WAO_EXPORT_MYSQL_PORT="${MYSQL_PORT:-3306}"
    ;;
  *) echo 'Unknown WAO MySQL writer role.' >&2; exit 1 ;;
esac
export WAO_EXPORT_MYSQL_USER="$DB_USER"
export MYSQL_PWD="$DB_PASS"
unset DB_ROOT_PASS DB_PASS
exec python3 /usr/local/lib/wao-backup/export-readable-content.py "$@"
