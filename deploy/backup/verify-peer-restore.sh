#!/usr/bin/env bash
# Read-only input, isolated disposable MySQL, no published ports or host DB mount.
set -Eeuo pipefail
umask 077
snapshot="${1:?Usage: verify-peer-restore.sh SNAPSHOT PASSPHRASE_FILE}"
passphrase="${2:?Missing passphrase file}"
[[ -d "$snapshot" && -s "$passphrase" && -f "$snapshot/COMPLETE" ]] || exit 2
(cd "$snapshot"; sha256sum -c SHA256SUMS)
temporary="$(mktemp -d "${TMPDIR:-/tmp}/wao-peer-restore.XXXXXX")"
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
if grep -qx 'format=2' "$temporary/recovery/metadata.txt"; then
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
gzip -dc "$temporary/recovery/wao.sql.gz" | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --max-allowed-packet=1G wao_restore'
docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema=\"wao_restore\" AND table_type=\"BASE TABLE\" ORDER BY table_name"' >"$temporary/restored-tables.txt"
diff -u "$temporary/recovery/tables.txt" "$temporary/restored-tables.txt"
python3 - "$temporary/restored-tables.txt" <<'PY' | docker exec -i "$container" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N wao_restore' >"$temporary/check.log"
from pathlib import Path
import sys
for name in Path(sys.argv[1]).read_text().splitlines():
    print('CHECK TABLE `' + name.replace('`', '``') + '`;')
PY
expected="$(wc -l <"$temporary/restored-tables.txt")"
awk -F '\t' -v expected="$expected" '$3 == "status" && $4 == "OK" { n++ } END { exit n != expected }' "$temporary/check.log"
tail -n 3 "$temporary/check.log"
printf 'Restored table count: %s\n' "$expected"
tar -tzf "$temporary/recovery/linux1-deploy.tar.gz" >/dev/null
tar -tzf "$temporary/recovery/linux2-deploy.tar.gz" >/dev/null
tar -tzf "$temporary/recovery/jenkins-home.tar.gz" >/dev/null
printf 'Verified isolated recovery: %s; all payload checksums, SQL import, table list and CHECK TABLE passed.\n' "$(basename "$snapshot")"
