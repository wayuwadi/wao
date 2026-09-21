#!/usr/bin/env bash
# Archive only closed, immutable binlogs. Credentials stay in process environment.
set -Eeuo pipefail
umask 077
. "${WAO_PEER_BACKUP_CONFIG:-/etc/wao-backup/peer.env}"
: "${PROJECT_ROOT:?}" "${BACKUP_ROOT:?}" "${PEER_HOST:?}" "${PEER_ROOT:?}" "${SSH_KEY:?}" "${KNOWN_HOSTS:?}" "${PASSPHRASE_FILE:?}"
[[ "$BACKUP_ROOT" == /* && "$BACKUP_ROOT" != / && "$BACKUP_ROOT" != *..* ]] || exit 2
[[ "$PEER_ROOT" =~ ^/[a-zA-Z0-9_/-]+$ && "$PEER_ROOT" != / && "$PEER_ROOT" != *..* ]] || exit 2
[[ "$PEER_HOST" =~ ^[a-zA-Z0-9_.@-]+$ && "$PEER_HOST" != -* ]] || exit 2
[[ -s "$PASSPHRASE_FILE" ]] || exit 1
install -d -m 0700 "$BACKUP_ROOT/binlogs"
exec 9>"$BACKUP_ROOT/binlogs/.lock"
flock -n 9 || { echo 'Binlog archive already running'; exit 1; }
set -a
. "$PROJECT_ROOT/deploy/.env"
set +a
export MYSQL_PWD="${DB_ROOT_PASS:?}"
mysql_args=(-uroot -h127.0.0.1 -P "${MYSQL_PORT:-3306}")
ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i "$SSH_KEY" -o "UserKnownHostsFile=$KNOWN_HOSTS")
peer() { ssh -n "${ssh_args[@]}" "$PEER_HOST" "$@"; }
peer_stream() { ssh "${ssh_args[@]}" "$PEER_HOST" "$@"; }
[[ "$(mysql "${mysql_args[@]}" -N -e 'SELECT @@read_only + @@super_read_only')" == 0 ]] || { echo 'Configured binlog source is not writable; operator must verify the authoritative writer'; exit 1; }
unexpected="$(mysql "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name NOT IN ('wao','tools','wayuwa','tkwork','mysql','sys','performance_schema','information_schema')")"
[[ "$unexpected" == 0 ]] || { echo 'Additional database detected; archive scope must be reviewed'; exit 1; }
uuid="$(mysql "${mysql_args[@]}" -N -e 'SELECT @@server_uuid')"
[[ "$uuid" =~ ^[0-9a-f-]{36}$ ]] || exit 2
root="$BACKUP_ROOT/binlogs/$uuid"
remote="$PEER_ROOT/binlogs/$uuid"
install -d -m 0700 "$root"
peer "umask 077; mkdir -p '$remote'"
temporary="$(mktemp -d "$BACKUP_ROOT/binlogs/.staging.XXXXXX")"
trap 'rm -rf -- "$temporary"' EXIT
trap 'exit 143' TERM HUP
trap 'exit 130' INT
# Rotate once, then read an exact closed prefix of the source's log list.
captured_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mysql "${mysql_args[@]}" -e 'FLUSH BINARY LOGS'
mysql "${mysql_args[@]}" -N -e 'SHOW BINARY LOGS' >"$temporary/logs"
python3 "$(dirname "$0")/plan-peer-binlogs.py" "$BACKUP_ROOT" "$uuid" "$temporary/logs" >"$temporary/closed"
while read -r name size encrypted; do
  [[ "$name" =~ ^[A-Za-z0-9_-]+\.[0-9]+$ && "$size" =~ ^[0-9]+$ ]] || exit 2
  if [[ -f "$root/$name/COMPLETE" ]]; then
    # Completed source archives are immutable; still verify the remote copy exists.
    peer "test -f '$remote/$name/COMPLETE'" || exit 1
    continue
  fi
  read -r total free < <(df -Pk "$root" | awk 'NR==2 {print $2,$4}')
  read -r peer_total peer_free < <(peer "df -Pk /home | awk 'NR==2 {print \$2,\$4}'")
  required=$((size / 1024 * 3 + 1048576))
  (( free - required > total / 5 && peer_free - required > peer_total / 5 )) || { echo 'Binlog archive would breach 20% disk reserve'; exit 1; }
  mkdir "$temporary/raw" "$temporary/publish"
  mysqlbinlog "${mysql_args[@]}" --read-from-remote-server --raw --result-file="$temporary/raw/" "$name"
  [[ "$(stat -c %s "$temporary/raw/$name")" == "$size" ]] || { echo 'Closed binlog length mismatch'; exit 1; }
  mysqlbinlog --verify-binlog-checksum "$temporary/raw/$name" >/dev/null
  raw_hash="$(sha256sum "$temporary/raw/$name" | cut -d' ' -f1)"
  gzip -1 -c "$temporary/raw/$name" | gpg --batch --yes --pinentry-mode loopback --passphrase-file "$PASSPHRASE_FILE" \
    --symmetric --cipher-algo AES256 --compress-algo none --output "$temporary/publish/binlog.gz.gpg"
  python3 - "$uuid" "$name" "$size" "$raw_hash" >"$temporary/publish/metadata.json" <<'PY'
import datetime, json, sys
print(json.dumps(dict(source_uuid=sys.argv[1], file=sys.argv[2], size=int(sys.argv[3]), sha256=sys.argv[4], archived_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())))
PY
  printf '%s\n' "$name" >"$temporary/publish/COMPLETE"
  (cd "$temporary/publish"; sha256sum binlog.gz.gpg metadata.json >SHA256SUMS)
  incoming=".incoming-$name-$$"
  peer "umask 077; test ! -e '$remote/$name'; mkdir '$remote/$incoming'"
  tar -C "$temporary/publish" -cf - . | peer_stream "tar -C '$remote/$incoming' -xf -"
  peer "set -e; cd '$remote/$incoming'; sha256sum -c SHA256SUMS; mv '$remote/$incoming' '$remote/$name'"
  mv "$temporary/publish" "$root/$name"
  rm -- "$temporary/raw/$name"
  rmdir "$temporary/raw"
  echo "Archived closed binlog: $name ($size bytes)"
done <"$temporary/closed"
# Persist the exact end of the last immutable file, not the timer's start time.
read -r last_name last_size _ < <(tail -1 "$temporary/closed")
[[ -n "$last_name" && -f "$root/$last_name/COMPLETE" ]] || exit 1
python3 - "$uuid" "$last_name" "$last_size" "$captured_utc" >"$temporary/last-success.json" <<'PY'
import datetime, json, sys
print(json.dumps(dict(source_uuid=sys.argv[1], file=sys.argv[2], position=int(sys.argv[3]), captured_utc=sys.argv[4], completed_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())))
PY
peer_stream "cat > '$PEER_ROOT/binlogs/last-success.json.tmp'; mv '$PEER_ROOT/binlogs/last-success.json.tmp' '$PEER_ROOT/binlogs/last-success.json'" <"$temporary/last-success.json"
mv "$temporary/last-success.json" "$BACKUP_ROOT/binlogs/last-success.json"
echo 'Closed binlog chain copied and verified on the peer.'
