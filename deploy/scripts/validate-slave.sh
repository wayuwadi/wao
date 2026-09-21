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

mysql_primary="$(sed -n 's/^WAO_MYSQL_PRIMARY=//p' "$env_file" | tr -d '\r')"
mysql_primary="${mysql_primary:-linux2}"
case "$mysql_primary" in linux1|linux2) ;; *) echo 'Unknown MySQL writer role'; exit 1 ;; esac
mysql_status="$(docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot --vertical -e "SHOW REPLICA STATUS"')"
if [ "$mysql_primary" = linux2 ]; then
printf '%s\n' "${mysql_status}" |
  grep -E 'Replica_IO_Running:|Replica_SQL_Running:|Seconds_Behind_Source:|Replicate_Wild_Do_Table:|Last_IO_Error:|Last_SQL_Error:' || true
printf '%s\n' "${mysql_status}" | grep -q 'Replica_IO_Running: Yes'
printf '%s\n' "${mysql_status}" | grep -q 'Replica_SQL_Running: Yes'
printf '%s\n' "${mysql_status}" | grep -Eq 'Seconds_Behind_Source: (0|[1-9][0-9]*)'
printf '%s\n' "${mysql_status}" | grep -Eq 'Replicate_Wild_Do_Table: wao\.%'
else
  test -z "$mysql_status" || { echo 'Promoted MySQL must not resume the previous replication channel.' >&2; exit 1; }
  grep -Fxq 'MYSQL_WRITER_HOST=10.66.0.3' "$env_file" || exit 1
fi

mysql_variables="$(docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -N -uroot -e "SHOW VARIABLES WHERE Variable_name IN ( '\''server_id'\'', '\''read_only'\'', '\''super_read_only'\'', '\''gtid_mode'\'' )"')"
printf '%s\n' "${mysql_variables}"
printf '%s\n' "${mysql_variables}" | grep -Eq '^server_id[[:space:]]+2$'
if [ "$mysql_primary" = linux2 ]; then
  printf '%s\n' "${mysql_variables}" | grep -Eq '^read_only[[:space:]]+ON$'
  printf '%s\n' "${mysql_variables}" | grep -Eq '^super_read_only[[:space:]]+ON$'
else
  printf '%s\n' "${mysql_variables}" | grep -Eq '^read_only[[:space:]]+OFF$'
  printf '%s\n' "${mysql_variables}" | grep -Eq '^super_read_only[[:space:]]+OFF$'
fi
printf '%s\n' "${mysql_variables}" | grep -Eq '^gtid_mode[[:space:]]+ON$'

application_schemas="$(docker exec "${mysql_id}" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -N -uroot -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME NOT IN ( '\''information_schema'\'', '\''mysql'\'', '\''performance_schema'\'', '\''sys'\'' ) ORDER BY SCHEMA_NAME"')"
test "${application_schemas}" = 'wao' || {
  echo "Unexpected application schemas on the replica: ${application_schemas}" >&2
  exit 1
}
echo 'application_schemas=wao'

# Redis roles follow Sentinel consensus, not the Linux master/slave deployment label.
redis_quorum="$(sed -n 's/^REDIS_HA_QUORUM=//p' "${env_file}" | tr -d '\r')"
case "$redis_quorum" in 2|4) ;; *) echo 'Missing reviewed REDIS_HA_QUORUM (2 or 4).' >&2; exit 1 ;; esac
python3 "${project_root}/deploy/redis/validate-private-ha.py" slave --quorum "$redis_quorum"

health="$(docker exec "${gateway_id}" curl -kfsS https://127.0.0.1:9000/actuator/health)"
hello="$(docker exec "${gateway_id}" curl -kfsS https://127.0.0.1:9000/api/hello)"
printf '%s' "${health}" | grep -q '"status":"UP"'
printf '%s' "${hello}" | grep -q '"code":0'
printf 'gateway_health=%s\n' "${health}"
printf 'gateway_api=%s\n' "${hello}"
