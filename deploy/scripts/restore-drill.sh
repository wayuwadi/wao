#!/bin/sh
set -eu

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"

backup_file="$(find /backups -maxdepth 1 -type f -name 'wao_*.sql.gz' | sort | tail -n 1)"
restore_db="wao_restore_test"

if [ -z "${backup_file}" ]; then
  echo "No backup file found" >&2
  exit 1
fi

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD}"
mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS ${restore_db}; CREATE DATABASE ${restore_db};"
gzip -dc "${backup_file}" | mysql -h127.0.0.1 -uroot "${restore_db}"
table_count="$(mysql -h127.0.0.1 -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${restore_db}';")"
mysql -h127.0.0.1 -uroot -e "DROP DATABASE ${restore_db};"

if [ "${table_count}" -le 0 ]; then
  echo "Restore drill failed: no tables restored" >&2
  exit 1
fi

echo "Restore drill passed with ${table_count} tables from ${backup_file}"
