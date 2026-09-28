#!/usr/bin/env bash
# Restore a writer snapshot into disposable MySQL; never connect to production DB.
set -Eeuo pipefail
umask 077

snapshot="${1:?Usage: verify-authoritative-wao-restore.sh SNAPSHOT PASSPHRASE_FILE}"
passphrase="${2:?Missing passphrase file}"
[[ -d "$snapshot" && -s "$passphrase" && -f "$snapshot/COMPLETE" ]] || exit 2
[[ -f "$snapshot/wao.sql.gz.gpg" && -f "$snapshot/recovery-point.json" ]] || exit 2
grep -qx 'wao-only source=linux1 created_utc=[0-9]\{8\}T[0-9]\{6\}Z' "$snapshot/COMPLETE"
(cd "$snapshot" && sha256sum -c SHA256SUMS)

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
stage_root="${WAO_PEER_RESTORE_STAGE_ROOT:-$(dirname "$snapshot")/.restore-work}"
install -d -m 0700 "$stage_root"
[[ "$(stat -f -c %T "$stage_root")" != tmpfs ]] || {
  echo 'Restore staging must be on disk.' >&2; exit 1;
}
python3 - "$stage_root" "$snapshot/wao.sql.gz.gpg" <<'PY'
import pathlib, shutil, sys
usage = shutil.disk_usage(sys.argv[1])
required = pathlib.Path(sys.argv[2]).stat().st_size * 2 + 12 * 1024**3
if usage.free < max(usage.total // 5, required):
    raise SystemExit('Insufficient disk reserve for isolated WAO restore.')
PY
exec 9>"$stage_root/.authoritative-wao-restore.lock"
flock -n 9 || { echo 'WAO isolated restore already running.' >&2; exit 1; }

stage="$(mktemp -d "$stage_root/authoritative-wao.XXXXXX")"
container="wao-authoritative-restore-$(date -u +%Y%m%d%H%M%S)-$$"
created=false
cleanup() {
  status=$?
  trap - EXIT
  if [[ "$created" == true ]]; then docker rm -fv "$container" >/dev/null; fi
  rm -rf -- "$stage"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$passphrase" \
  --output "$stage/wao.sql.gz" --decrypt "$snapshot/wao.sql.gz.gpg"
gzip -t "$stage/wao.sql.gz"
python3 "$script_dir/inspect-authoritative-wao-snapshot.py" "$stage/wao.sql.gz" \
  >"$stage/inspected.json"
python3 - "$snapshot/recovery-point.json" "$stage/inspected.json" <<'PY'
import json, pathlib, sys
recorded, inspected = (json.loads(pathlib.Path(p).read_text()) for p in sys.argv[1:])
if recorded != inspected or recorded['database'] != 'wao':
    raise SystemExit('Encrypted dump and published WAO recovery point differ.')
PY
expected="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["source_table_count"])' "$stage/inspected.json")"

printf 'MYSQL_ROOT_PASSWORD=' >"$stage/mysql.env"
openssl rand -hex 32 >>"$stage/mysql.env"
docker image inspect mysql:9.7.0 >/dev/null
docker create --name "$container" --network none --memory 2g --cpus 1 \
  --env-file "$stage/mysql.env" mysql:9.7.0 \
  --skip-networking --event-scheduler=OFF --skip-log-bin --max-allowed-packet=1G >/dev/null
created=true
docker start "$container" >/dev/null
ready=false
for attempt in $(seq 1 120); do
  if docker exec "$container" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT 1"' >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Isolated MySQL did not become ready.' >&2; exit 1; }

gzip -dc "$stage/wao.sql.gz" | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=1G'
schemas="$(docker exec "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT GROUP_CONCAT(schema_name ORDER BY schema_name) FROM information_schema.schemata WHERE schema_name NOT IN (\"information_schema\",\"mysql\",\"performance_schema\",\"sys\")"')"
[[ "$schemas" == wao ]] || { echo 'Unexpected schema in isolated restore.' >&2; exit 1; }
docker exec "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema=\"wao\" AND table_type=\"BASE TABLE\" ORDER BY table_name"' \
  >"$stage/tables.txt"
actual="$(wc -l <"$stage/tables.txt" | tr -d ' ')"
[[ "$actual" == "$expected" ]] || {
  echo "Restored WAO table count $actual differs from source inventory $expected." >&2; exit 1;
}
python3 - "$stage/tables.txt" <<'PY' | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N' >"$stage/check.log"
from pathlib import Path
import sys
for name in Path(sys.argv[1]).read_text().splitlines():
    print('CHECK TABLE `wao`.`' + name.replace('`', '``') + '`;')
PY
awk -F '\t' -v expected="$expected" \
  '$3 == "status" && $4 == "OK" { n++ } END { exit n != expected }' "$stage/check.log"
printf 'Verified isolated WAO recovery: %s tables imported and checked.\n' "$expected"
