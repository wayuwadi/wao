#!/usr/bin/env bash
# Read-only input, isolated disposable MySQL, no published ports or host DB mount.
set -Eeuo pipefail
umask 077
snapshot="${1:?Usage: verify-peer-restore.sh SNAPSHOT PASSPHRASE_FILE}"
passphrase="${2:?Missing passphrase file}"
[[ -d "$snapshot" && -s "$passphrase" && -f "$snapshot/COMPLETE" ]] || exit 2
(cd "$snapshot"; sha256sum -c SHA256SUMS)
# /tmp can be tmpfs: a multi-gigabyte encrypted archive must use disk staging.
stage_root="${WAO_PEER_RESTORE_STAGE_ROOT:-$(dirname "$snapshot")/.restore-work}"
mkdir -p "$stage_root"
[[ "$(stat -f -c %T "$stage_root")" != tmpfs ]] || { echo 'Restore staging must be on disk, not tmpfs.' >&2; exit 1; }
python3 - "$stage_root" "$snapshot/recovery.tar.gpg" <<'PY'
import pathlib, shutil, sys
usage = shutil.disk_usage(sys.argv[1])
required = pathlib.Path(sys.argv[2]).stat().st_size * 2 + 12 * 1024**3
if usage.free < max(usage.total // 5, required):
    raise SystemExit('Insufficient disk reserve for isolated restore staging.')
PY
temporary="$(mktemp -d "$stage_root/wao-peer-restore.XXXXXX")"
container="wao-peer-restore-$(date -u +%Y%m%d%H%M%S)-$$"
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
gpg --batch --pinentry-mode loopback --passphrase-file "$passphrase" --decrypt "$snapshot/recovery.tar.gpg" \
  | tar -C "$temporary" -xf -
(cd "$temporary/recovery"; sha256sum -c SHA256SUMS)
format="$(sed -n 's/^format=//p' "$temporary/recovery/metadata.txt")"
[[ "$format" =~ ^[123]$ ]] || { echo 'Unsupported snapshot format'; exit 1; }
if [[ "$format" == 2 || "$format" == 3 ]]; then
  python3 - "$temporary/recovery" <<'PY'
import json, pathlib, sys, tarfile
for node in ('linux1', 'linux2'):
    with tarfile.open(pathlib.Path(sys.argv[1]) / f'{node}-redis-state.tar.gz') as archive:
        assert set(archive.getnames()) == {'redis.conf', 'sentinel.conf', 'role.json'}
        assert all(member.isfile() for member in archive.getmembers())
        assert json.load(archive.extractfile('role.json'))[0] in ('master', 'slave')
        assert b'sentinel myid ' in archive.extractfile('sentinel.conf').read()
        assert b'requirepass ' in archive.extractfile('redis.conf').read()
print('Both protected Redis configuration archives are structurally valid; no historical role was applied.')
PY
fi
printf 'MYSQL_ROOT_PASSWORD=' >"$temporary/mysql.env"
openssl rand -hex 32 >>"$temporary/mysql.env"
printf 'MYSQL_DATABASE=wao_restore\n' >>"$temporary/mysql.env"
docker create --name "$container" --network none --memory 2g --cpus 1 --env-file "$temporary/mysql.env" \
  mysql:9.7.0 --skip-networking --event-scheduler=OFF --skip-log-bin --max-allowed-packet=1G >/dev/null
created=true
docker start "$container" >/dev/null
ready=false
for attempt in $(seq 1 120); do
  if docker exec "$container" sh -c 'test "$(cat /proc/1/comm)" = mysqld && MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N wao_restore -e "SELECT 1"' >/dev/null 2>&1; then
    ready=true; break
  fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Isolated restore database did not become ready.' >&2; exit 1; }
if [[ "$format" == 3 ]]; then
  gzip -dc "$temporary/recovery/databases.sql.gz" | docker exec -i "$container" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=1G'
  docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT CONCAT(table_schema,CHAR(46),table_name) FROM information_schema.tables WHERE table_schema IN (CHAR(119,97,111),CHAR(116,111,111,108,115),CHAR(119,97,121,117,119,97),CHAR(116,107,119,111,114,107)) AND table_type=\"BASE TABLE\" ORDER BY table_schema,table_name"' >"$temporary/restored-tables.txt"
else
gzip -dc "$temporary/recovery/wao.sql.gz" | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=1G wao_restore'
docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema=\"wao_restore\" AND table_type=\"BASE TABLE\" ORDER BY table_name"' >"$temporary/restored-tables.txt"
fi
diff -u "$temporary/recovery/tables.txt" "$temporary/restored-tables.txt"
python3 - "$temporary/restored-tables.txt" "$format" <<'PY' | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N wao_restore' >"$temporary/check.log"
from pathlib import Path
import sys
for name in Path(sys.argv[1]).read_text().splitlines():
    parts = name.split('.', 1) if sys.argv[2] == '3' else [name]
    print('CHECK TABLE ' + '.'.join('`' + part.replace('`', '``') + '`' for part in parts) + ';')
PY
expected="$(wc -l <"$temporary/restored-tables.txt")"
awk -F '\t' -v expected="$expected" '$3 == "status" && $4 == "OK" { n++ } END { exit n != expected }' "$temporary/check.log"
tail -n 3 "$temporary/check.log"
printf 'Restored table count: %s\n' "$expected"
if [[ -n "${3:-}" ]]; then
  [[ "$format" == 3 ]] || { echo 'PITR requires format 3 snapshot coordinates'; exit 1; }
  target="$3"
  archive_root="${WAO_PEER_BINLOG_ROOT:-$(dirname "$snapshot")/binlogs}"
  python3 "$(dirname "$0")/prepare-peer-pitr.py" "$temporary/recovery/recovery-point.json" "$target" \
    "$archive_root" "$temporary/pitr" "$passphrase" >"$temporary/pitr-options"
  mapfile -t pitr_options <"$temporary/pitr-options"
  tools_image="${WAO_MYSQL_RECOVERY_TOOLS_IMAGE:-wao-mysql-recovery-tools:9.7.0}"
  docker image inspect "$tools_image" >/dev/null
  # The official minimal server image has no mysqlbinlog. Use a version-matched,
  # signed client-tools image with only the verified raw logs mounted read-only.
  docker run --rm --network none --memory 512m --cpus 1 --read-only \
    --volume "$temporary/pitr:/tmp/wao-pitr:ro" "$tools_image" "${pitr_options[@]}" | docker exec -i "$container" sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=1G'
  if [[ -n "${WAO_PITR_ASSERT_SQL:-}" ]]; then
    [[ -f "$WAO_PITR_ASSERT_SQL" ]] || exit 2
    docker exec -i "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N' <"$WAO_PITR_ASSERT_SQL" >"$temporary/pitr-result"
    [[ "$(cat "$temporary/pitr-result")" == PASS ]] || { echo 'PITR acceptance marker mismatch'; exit 1; }
  fi
  echo 'Verified continuous binlog replay to the requested file position in the isolated container.'
fi
tar -tzf "$temporary/recovery/linux1-deploy.tar.gz" >/dev/null
tar -tzf "$temporary/recovery/linux2-deploy.tar.gz" >/dev/null
if [[ -f "$temporary/recovery/jenkins-home.tar.gz" ]]; then
  tar -tzf "$temporary/recovery/jenkins-home.tar.gz" >/dev/null
else
  [[ "$format" == 3 ]] && grep -Eq '^jenkins=(unavailable|weekly-not-scheduled)$' "$temporary/recovery/metadata.txt"
  echo 'Database recovery passed independently; restore Jenkins from a snapshot marked jenkins=included.'
fi
printf 'Verified isolated recovery: %s; all payload checksums, SQL import, table list and CHECK TABLE passed.\n' "$(basename "$snapshot")"
