#!/usr/bin/env bash
# Run over authenticated SSH on Linux1. Stdout is only a rate-limited gzip stream.
set -Eeuo pipefail
umask 077

env_file="${WAO_WRITER_ENV_FILE:-/home/wage/deploy/wao/deploy/.env}"
[[ -f "$env_file" ]] || { echo 'Missing Linux1 writer configuration.' >&2; exit 1; }
# The deployment environment is generated as shell-safe assignments.
. "$env_file"
[[ "${WAO_MYSQL_PRIMARY:-}" == linux1 ]] || {
  echo 'Linux1 is not the declared WAO writer.' >&2; exit 1;
}
rate="${WAO_SNAPSHOT_RATE_BPS:-524288}"
[[ "$rate" =~ ^[0-9]+$ ]] && (( rate >= 65536 && rate <= 2097152 )) || {
  echo 'WAO_SNAPSHOT_RATE_BPS must be between 65536 and 2097152.' >&2; exit 2;
}
container="${WAO_WRITER_MYSQL_CONTAINER:-wao-slave-mysql-slave-1}"
[[ "$container" =~ ^[a-zA-Z0-9_-]+$ ]] || exit 2
export MYSQL_PWD="${DB_ROOT_PASS:?}"

state="$(docker exec -e MYSQL_PWD "$container" mysql -uroot -N -e \
  'SELECT @@server_uuid, @@global.read_only, @@global.super_read_only' 2>/dev/null)"
read -r writer_uuid read_only super_read_only <<<"$state"
[[ "$writer_uuid" =~ ^[0-9a-f-]{36}$ && "$read_only" == 0 && "$super_read_only" == 0 ]] || {
  echo 'Linux1 MySQL is not a verified writable source.' >&2; exit 1;
}
printf 'Verified Linux1 WAO writer UUID: %s\n' "$writer_uuid" >&2
table_count="$(docker exec -e MYSQL_PWD "$container" mysql -uroot -N -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='wao' AND table_type='BASE TABLE'" 2>/dev/null)"
[[ "$table_count" =~ ^[0-9]+$ ]] && (( table_count > 0 )) || {
  echo 'Linux1 WAO table inventory is unavailable.' >&2; exit 1;
}

# Throttle the compressed bytes before they enter the WAN. The previous
# uncompressed SQL stream saturated Linux1's uplink and delayed both Gateways.
{
  printf -- '-- WAO_SOURCE_UUID=%s\n' "$writer_uuid"
  printf -- '-- WAO_SOURCE_TABLES=%s\n' "$table_count"
  docker exec -e MYSQL_PWD "$container" mysqldump -uroot \
  --single-transaction --quick --source-data=2 --set-gtid-purged=COMMENTED \
  --add-drop-database --routines --events --triggers --hex-blob --no-tablespaces \
    --max-allowed-packet=1G --databases wao
} | gzip -1 \
  | WAO_SNAPSHOT_RATE_BPS="$rate" python3 -c '
import os, sys, time
rate = int(os.environ["WAO_SNAPSHOT_RATE_BPS"])
start = time.monotonic()
sent = 0
while chunk := sys.stdin.buffer.read(65536):
    sys.stdout.buffer.write(chunk)
    sys.stdout.buffer.flush()
    sent += len(chunk)
    remaining = sent / rate - (time.monotonic() - start)
    if remaining > 0:
        time.sleep(remaining)
'
