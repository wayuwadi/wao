#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
env_file="${project_root}/deploy/.env"
compose_file="${project_root}/deploy/docker-compose-slave.yml"

if [ -f "${project_root}/deploy/.public-ip" ]; then
  NODE_PUBLIC_IP="$(sed -n '1p' "${project_root}/deploy/.public-ip" | tr -d '\r\n')"
  export NODE_PUBLIC_IP
fi

compose() {
  docker compose --env-file "${env_file}" -f "${compose_file}" "$@"
}

mysql_id="$(compose ps -q mysql-slave)"
redis_id="$(compose ps -q redis-slave)"
sentinel_id="$(compose ps -q sentinel-slave)"
gateway_id="$(compose ps -q wao-gateway)"

test -n "${mysql_id}${redis_id}${sentinel_id}${gateway_id}" || {
  echo 'One or more required slave containers are missing.' >&2
  exit 1
}

mysql_status="$(docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --vertical -e "SHOW REPLICA STATUS"')"
printf '%s\n' "${mysql_status}" |
  grep -E 'Replica_IO_Running:|Replica_SQL_Running:|Seconds_Behind_Source:|Replicate_Wild_Do_Table:|Last_IO_Error:|Last_SQL_Error:' || true
printf '%s\n' "${mysql_status}" | grep -q 'Replica_IO_Running: Yes'
printf '%s\n' "${mysql_status}" | grep -q 'Replica_SQL_Running: Yes'
printf '%s\n' "${mysql_status}" | grep -Eq 'Seconds_Behind_Source: (0|[1-9][0-9]*)'
printf '%s\n' "${mysql_status}" | grep -Eq 'Replicate_Wild_Do_Table: wao\.%'

mysql_variables="$(docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -N -uroot -e "SHOW VARIABLES WHERE Variable_name IN ( '\''server_id'\'', '\''read_only'\'', '\''super_read_only'\'', '\''gtid_mode'\'' )"')"
printf '%s\n' "${mysql_variables}"
printf '%s\n' "${mysql_variables}" | grep -Eq '^server_id[[:space:]]+2$'
printf '%s\n' "${mysql_variables}" | grep -Eq '^read_only[[:space:]]+ON$'
printf '%s\n' "${mysql_variables}" | grep -Eq '^super_read_only[[:space:]]+ON$'
printf '%s\n' "${mysql_variables}" | grep -Eq '^gtid_mode[[:space:]]+ON$'

application_schemas="$(docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -N -uroot -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME NOT IN ( '\''information_schema'\'', '\''mysql'\'', '\''performance_schema'\'', '\''sys'\'' ) ORDER BY SCHEMA_NAME"')"
test "${application_schemas}" = 'wao' || {
  echo "Unexpected application schemas on the replica: ${application_schemas}" >&2
  exit 1
}
echo 'application_schemas=wao'

redis_status="$(docker exec "${redis_id}" sh -c 'redis-cli --no-auth-warning -a "$REDIS_PASS" INFO replication')"
printf '%s\n' "${redis_status}" | grep -q '^role:slave'
printf '%s\n' "${redis_status}" | grep -q '^master_link_status:up'
printf '%s\n' "${redis_status}" |
  grep -E '^role:|^master_host:|^master_link_status:|^master_sync_in_progress:|^master_last_io_seconds_ago:' |
  tr -d '\r'

sentinel_master="$(docker exec "${sentinel_id}" sh -c 'redis-cli --no-auth-warning -p 26379 -a "$REDIS_PASS" SENTINEL get-master-addr-by-name "${REDIS_MASTER_NAME:-wao-master}"')"
test -n "${sentinel_master}" || { echo 'Sentinel did not return a master address.' >&2; exit 1; }
expected_master_host="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "${sentinel_id}" |
  sed -n 's/^REDIS_MASTER_HOST=//p')"
sentinel_master_host="$(printf '%s\n' "${sentinel_master}" | sed -n '1p' | tr -d '\r')"
sentinel_master_port="$(printf '%s\n' "${sentinel_master}" | sed -n '2p' | tr -d '\r')"
test -n "${expected_master_host}" || { echo 'Sentinel has no declared Redis master host.' >&2; exit 1; }
if [ "${sentinel_master_host}:${sentinel_master_port}" != "${expected_master_host}:6379" ]; then
  echo "Sentinel points to ${sentinel_master_host}:${sentinel_master_port}; expected ${expected_master_host}:6379." >&2
  exit 1
fi
printf 'sentinel_master=%s:%s\n' "${sentinel_master_host}" "${sentinel_master_port}"

health="$(docker exec "${gateway_id}" curl -kfsS https://127.0.0.1:9000/actuator/health)"
hello="$(docker exec "${gateway_id}" curl -kfsS https://127.0.0.1:9000/api/hello)"
printf '%s' "${health}" | grep -q '"status":"UP"'
printf '%s' "${hello}" | grep -q '"code":0'
printf 'gateway_health=%s\n' "${health}"
printf 'gateway_api=%s\n' "${hello}"
