#!/usr/bin/env bash
# Linux2-only, four-database encrypted recovery point. Never prunes old copies.
set -Eeuo pipefail
umask 077
. "${WAO_CLOUD_BACKUP_CONFIG:-/etc/wao-backup/cloud.env}"
: "${PROJECT_ROOT:?}" "${BACKUP_ROOT:?}" "${CLOUD_ROOT:?}" "${CLOUD_HOST:?}"
: "${SSH_KEY:?}" "${KNOWN_HOSTS:?}" "${PASSPHRASE_FILE:?}"
[[ "$BACKUP_ROOT" == /* && "$BACKUP_ROOT" != / && "$BACKUP_ROOT" != *..* ]] || exit 2
[[ "$CLOUD_ROOT" == /* && "$CLOUD_ROOT" != / && "$CLOUD_ROOT" != *..* ]] || exit 2
[[ "$CLOUD_HOST" =~ ^[a-zA-Z0-9_.@-]+$ && "$CLOUD_HOST" != -* ]] || exit 2
[[ -s "$PASSPHRASE_FILE" && -f "$SSH_KEY" && -f "$KNOWN_HOSTS" ]] || exit 1
[[ -f "$PROJECT_ROOT/deploy/.env" ]] || exit 1
grep -Fqx 'WAO_DEPLOY_TOPOLOGY=linux2-only' "$PROJECT_ROOT/deploy/.env" || {
  echo 'Refusing a cloud snapshot outside Linux2-only topology.' >&2; exit 1;
}
install -d -m 0700 "$BACKUP_ROOT"
exec 9>"$BACKUP_ROOT/.lock"
flock -n 9 || { echo 'Cloud backup already running.' >&2; exit 1; }
ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i "$SSH_KEY"
  -o "UserKnownHostsFile=$KNOWN_HOSTS")
cloud() { ssh "${ssh_args[@]}" "$CLOUD_HOST" "$@"; }

read -r local_total local_free < <(df -Pk "$BACKUP_ROOT" | awk 'NR==2 {print $2,$4}')
read -r cloud_total cloud_free < <(cloud "df -Pk '$CLOUD_ROOT' | awk 'NR==2 {print \$2,\$4}'")
reserve=6291456 # 6 GiB working space and a 20% disk reserve on both hosts.
(( local_free - reserve > local_total / 5 && cloud_free - reserve > cloud_total / 5 )) || {
  echo 'Insufficient local or cloud capacity for an independent encrypted snapshot.' >&2; exit 1;
}

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
staging="$(mktemp -d "$BACKUP_ROOT/.staging-$stamp.XXXXXX")"
lock_pid=''
cleanup() {
  status=$?
  trap - EXIT
  if [[ -n "$lock_pid" ]]; then
    exec 8>&- || true
    kill "$lock_pid" 2>/dev/null || true
    wait "$lock_pid" 2>/dev/null || true
  fi
  rm -rf -- "$staging"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
mkdir -m 0700 "$staging/recovery" "$staging/publish"

tar -C "$PROJECT_ROOT/deploy" --exclude='./backups' --exclude='./.public-ip' \
  -czf "$staging/recovery/linux2-deploy.tar.gz" .
gzip -t "$staging/recovery/linux2-deploy.tar.gz"
set -a
. "$PROJECT_ROOT/deploy/.env"
set +a
: "${DB_ROOT_PASS:?}"
source_kind="$(python3 "$(dirname "$0")/verify-backup-source.py")"
[[ "$source_kind" == linux2-four-database-writer ]] || {
  echo "Unexpected MySQL backup source: $source_kind" >&2; exit 1;
}
export MYSQL_PWD="$DB_ROOT_PASS"
mysql_args=(-uroot -h127.0.0.1 -P "${MYSQL_PORT:-3306}")
mkfifo "$staging/ddl-lock.fifo"
mysql "${mysql_args[@]}" --batch --skip-column-names --unbuffered --skip-reconnect \
  <"$staging/ddl-lock.fifo" >"$staging/ddl-lock.log" 2>&1 &
lock_pid=$!
exec 8>"$staging/ddl-lock.fifo"
printf '%s\n' "SET SESSION lock_wait_timeout=30; LOCK INSTANCE FOR BACKUP; SELECT 'WAO_BACKUP_LOCKED';" >&8
lock_ready=false
for attempt in $(seq 1 35); do
  if grep -qx WAO_BACKUP_LOCKED "$staging/ddl-lock.log"; then lock_ready=true; break; fi
  kill -0 "$lock_pid" 2>/dev/null || break
  sleep 1
done
[[ "$lock_ready" == true ]] || { echo 'Could not acquire MySQL DDL backup lock.' >&2; exit 1; }
filter="'wao','tools','wayuwa','tkwork'"
[[ "$(mysql "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ($filter)")" == 4 ]] || exit 1
[[ "$(mysql "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ($filter) AND table_type='BASE TABLE' AND engine <> 'InnoDB'")" == 0 ]] || exit 1
source_uuid="$(mysql "${mysql_args[@]}" -N -e 'SELECT @@server_uuid')"
mysqldump "${mysql_args[@]}" --single-transaction --quick --skip-lock-tables \
  --set-gtid-purged=OFF --source-data=2 --routines --events --triggers \
  --hex-blob --no-tablespaces --databases wao tools wayuwa tkwork \
  | gzip -1 >"$staging/recovery/databases.sql.gz"
kill -0 "$lock_pid"
printf 'UNLOCK INSTANCE;\n' >&8
exec 8>&-
wait "$lock_pid"
lock_pid=''
unset MYSQL_PWD DB_ROOT_PASS
gzip -t "$staging/recovery/databases.sql.gz"
python3 - "$staging/recovery" "$source_uuid" <<'PY'
import gzip, json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
with gzip.open(root / 'databases.sql.gz', 'rt') as dump:
    for line in dump:
        match = re.fullmatch(r"-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='([A-Za-z0-9_.-]+)', SOURCE_LOG_POS=(\d+);\n", line)
        if match:
            (root / 'recovery-point.json').write_text(json.dumps({
                'source_uuid': sys.argv[2], 'file': match[1],
                'position': int(match[2]), 'databases': ['wao', 'tools', 'wayuwa', 'tkwork']}))
            break
    else:
        raise SystemExit('No consistent binary-log position in database dump')
PY
printf 'format=1\ncreated_utc=%s\nsource=%s\n' "$stamp" "$source_kind" >"$staging/recovery/metadata.txt"
(cd "$staging/recovery"; sha256sum databases.sql.gz linux2-deploy.tar.gz recovery-point.json metadata.txt >SHA256SUMS)
tar -C "$staging" -cf - recovery | gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "$PASSPHRASE_FILE" --symmetric --cipher-algo AES256 \
  --compress-algo none --output "$staging/publish/recovery.tar.gpg"
cp "$staging/recovery/recovery-point.json" "$staging/publish/recovery-point.json"
printf '%s\n' "$stamp" >"$staging/publish/COMPLETE"
(cd "$staging/publish"; sha256sum recovery.tar.gpg recovery-point.json >SHA256SUMS)

cloud "set -e; umask 077; mkdir -p '$CLOUD_ROOT'; test ! -e '$CLOUD_ROOT/$stamp'; mkdir '$CLOUD_ROOT/.incoming-$stamp'"
tar -C "$staging/publish" -cf - recovery.tar.gpg recovery-point.json SHA256SUMS COMPLETE \
  | cloud "tar -C '$CLOUD_ROOT/.incoming-$stamp' -xf -"
cloud "set -e; cd '$CLOUD_ROOT/.incoming-$stamp'; sha256sum -c SHA256SUMS; mv '$CLOUD_ROOT/.incoming-$stamp' '$CLOUD_ROOT/$stamp'"
mv "$staging/publish" "$BACKUP_ROOT/$stamp"
printf '%s\n' "$stamp" >"$BACKUP_ROOT/last-success.tmp"
mv "$BACKUP_ROOT/last-success.tmp" "$BACKUP_ROOT/last-success"
echo "Verified Linux2/cloud backup: $stamp"
