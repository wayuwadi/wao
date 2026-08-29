#!/bin/sh
set -eu

snapshot="${1:?Usage: bootstrap-mysql-slave.sh SNAPSHOT.sql.gz}"
test -f "${snapshot}" || { echo "Snapshot not found: ${snapshot}" >&2; exit 1; }

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
env_file="${project_root}/deploy/.env"
compose_file="${project_root}/deploy/docker-compose-slave.yml"
secrets_file="${project_root}/deploy/.env.secrets"

compose() {
  if [ -f "${secrets_file}" ]; then
    docker compose --env-file "${env_file}" --env-file "${secrets_file}" \
      -f "${compose_file}" "$@"
  else
    docker compose --env-file "${env_file}" -f "${compose_file}" "$@"
  fi
}

# BOOTSTRAP_SLAVE is an explicit full replica rebuild. Recreate the data
# volume so stale root credentials or partial data from an interrupted prior
# bootstrap cannot affect the new snapshot import.
compose stop mysql-slave
compose rm -f mysql-slave
if docker volume inspect wao-slave_mysql-slave-data >/dev/null 2>&1; then
  docker volume rm wao-slave_mysql-slave-data
fi
compose up -d mysql-slave

mysql_id="$(compose ps -q mysql-slave)"
test -n "${mysql_id}" || { echo 'MySQL replica container is not running.' >&2; exit 1; }

mysql_ready=false
attempt=1
while [ "${attempt}" -le 60 ]; do
  if docker exec "${mysql_id}" sh -c \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -N -uroot -e "SELECT 1"' \
      >/dev/null 2>&1; then
    mysql_ready=true
    break
  fi
  sleep 2
  attempt=$((attempt + 1))
done
test "${mysql_ready}" = true || {
  echo 'MySQL replica did not become ready for snapshot bootstrap.' >&2
  exit 1
}

docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
SET GLOBAL super_read_only=OFF;
SET GLOBAL read_only=OFF;
STOP REPLICA;
RESET REPLICA ALL;
RESET BINARY LOGS AND GTIDS;
DROP DATABASE IF EXISTS wao;
SQL'

gzip -dc "${snapshot}" |
  docker exec -i "${mysql_id}" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --max-allowed-packet=1G -uroot'

docker exec "${mysql_id}" sh /docker-entrypoint-initdb.d/10-replication.sh
echo 'MySQL replica snapshot bootstrap completed.'
