#!/usr/bin/env bash
# Decrypt and import a completed snapshot only into an isolated disposable MySQL.
set -Eeuo pipefail
umask 077
snapshot="${1:?Usage: verify-cloud-backup.sh SNAPSHOT PASSPHRASE_FILE}"
passphrase="${2:?Missing passphrase file}"
[[ -d "$snapshot" && -s "$passphrase" && -f "$snapshot/COMPLETE" ]] || exit 2
(cd "$snapshot"; sha256sum -c SHA256SUMS)
stage_root="${WAO_CLOUD_RESTORE_STAGE_ROOT:-$(dirname "$snapshot")/.restore-work}"
install -d -m 0700 "$stage_root"
[[ "$(stat -f -c %T "$stage_root")" != tmpfs ]] || exit 1
temporary="$(mktemp -d "$stage_root/wao-cloud-restore.XXXXXX")"
container="wao-cloud-restore-$(date -u +%Y%m%d%H%M%S)-$$"
created=false
cleanup() {
  status=$?
  trap - EXIT
  if [[ "$created" == true ]]; then docker rm -fv "$container" >/dev/null; fi
  rm -rf -- "$temporary"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
gpg --batch --pinentry-mode loopback --passphrase-file "$passphrase" \
  --decrypt "$snapshot/recovery.tar.gpg" | tar -C "$temporary" -xf -
(cd "$temporary/recovery"; sha256sum -c SHA256SUMS)
gzip -t "$temporary/recovery/databases.sql.gz"
tar -tzf "$temporary/recovery/linux2-deploy.tar.gz" >/dev/null
python3 - "$temporary/recovery/recovery-point.json" <<'PY'
import json, pathlib, re, sys
point = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert re.fullmatch(r'[0-9a-f-]{36}', point['source_uuid'])
assert re.fullmatch(r'[A-Za-z0-9_-]+\.\d+', point['file'])
assert isinstance(point['position'], int) and point['position'] > 0
assert point['databases'] == ['wao', 'tools', 'wayuwa', 'tkwork']
PY
printf 'MYSQL_ROOT_PASSWORD=' >"$temporary/mysql.env"
openssl rand -hex 32 >>"$temporary/mysql.env"
docker create --name "$container" --network none --memory 2g --cpus 1 \
  --env-file "$temporary/mysql.env" mysql:9.7.0 --skip-networking \
  --event-scheduler=OFF --skip-log-bin --max-allowed-packet=1G >/dev/null
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
gzip -dc "$temporary/recovery/databases.sql.gz" | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=1G'
docker exec "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT table_schema, COUNT(*) FROM information_schema.tables WHERE table_schema IN (CHAR(119,97,111),CHAR(116,111,111,108,115),CHAR(119,97,121,117,119,97),CHAR(116,107,119,111,114,107)) AND table_type=\"BASE TABLE\" GROUP BY table_schema ORDER BY table_schema"' \
  >"$temporary/table-counts.txt"
[[ "$(wc -l <"$temporary/table-counts.txt")" == 4 ]] || exit 1
awk '$2 < 1 { exit 1 }' "$temporary/table-counts.txt"
cat "$temporary/table-counts.txt"
echo "Verified isolated four-database recovery: $(basename "$snapshot")"
