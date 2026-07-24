#!/bin/sh
set -eu

snapshot="${1:?Usage: bootstrap-mysql-slave.sh SNAPSHOT.sql.gz}"
test -f "${snapshot}" || { echo "Snapshot not found: ${snapshot}" >&2; exit 1; }

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
env_file="${project_root}/deploy/.env"
compose_file="${project_root}/deploy/docker-compose-slave.yml"
mysql_id="$(docker compose --env-file "${env_file}" -f "${compose_file}" ps -q mysql-slave)"
test -n "${mysql_id}" || { echo 'MySQL replica container is not running.' >&2; exit 1; }

docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot <<SQL
SET GLOBAL super_read_only=OFF;
SET GLOBAL read_only=OFF;
STOP REPLICA;
RESET REPLICA ALL;
RESET BINARY LOGS AND GTIDS;
DROP DATABASE IF EXISTS wao;
SQL'

gzip -dc "${snapshot}" |
  docker exec -i "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot'

docker exec "${mysql_id}" sh /docker-entrypoint-initdb.d/10-replication.sh
echo 'MySQL replica snapshot bootstrap completed.'
