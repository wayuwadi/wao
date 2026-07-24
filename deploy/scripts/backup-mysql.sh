#!/bin/sh
set -eu

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
: "${DB_NAME:?DB_NAME is required}"

timestamp="$(date +%Y%m%d_%H%M%S)"
target="/backups/wao_${timestamp}.sql.gz"

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysqldump \
  --host=127.0.0.1 \
  --user=root \
  --single-transaction \
  --set-gtid-purged=OFF \
  --routines \
  --events \
  "${DB_NAME}" | gzip -c >"${target}"

find /backups -type f -name 'wao_*.sql.gz' -mtime +7 -delete
echo "Backup created: ${target}"
