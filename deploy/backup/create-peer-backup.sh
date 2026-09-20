#!/usr/bin/env bash
# Independent WAO recovery snapshots. Replication is deliberately not the source.
set -Eeuo pipefail
umask 077
config="${WAO_PEER_BACKUP_CONFIG:-/etc/wao-backup/peer.env}"
[[ -f "$config" ]] || { echo "Missing peer backup configuration: $config" >&2; exit 1; }
# Root-owned shell configuration; never enable tracing here.
. "$config"
: "${PROJECT_ROOT:?}" "${BACKUP_ROOT:?}" "${PEER_HOST:?}" "${PEER_ROOT:?}"
: "${SSH_KEY:?}" "${KNOWN_HOSTS:?}" "${PASSPHRASE_FILE:?}" "${PEER_DEPLOY_ROOT:?}"
[[ "$BACKUP_ROOT" == /* && "$BACKUP_ROOT" != / && "$BACKUP_ROOT" != *..* ]] || exit 2
[[ "$PEER_ROOT" =~ ^/[a-zA-Z0-9_/-]+$ && "$PEER_ROOT" != / && "$PEER_ROOT" != *..* ]] || exit 2
[[ "$PEER_HOST" =~ ^[a-zA-Z0-9_.@-]+$ && "$PEER_HOST" != -* ]] || exit 2
[[ "$PEER_DEPLOY_ROOT" =~ ^/[a-zA-Z0-9_/-]+$ && "$PEER_DEPLOY_ROOT" != / ]] || exit 2
[[ -s "$PASSPHRASE_FILE" && -f "$PROJECT_ROOT/deploy/.env" ]] || exit 1
install -d -m 0700 "$BACKUP_ROOT"
exec 9>"$BACKUP_ROOT/.lock"
flock -n 9 || { echo 'Peer backup already running.'; exit 1; }
ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -i "$SSH_KEY" -o "UserKnownHostsFile=$KNOWN_HOSTS")
peer() { ssh "${ssh_args[@]}" "$PEER_HOST" "$@"; }
local_free="$(df -Pk "$BACKUP_ROOT" | awk 'NR==2 {print $4}')"
peer_free="$(peer "df -Pk /home | awk 'NR==2 {print \$4}'")"
(( local_free >= 12582912 && peer_free >= 12582912 )) || { echo 'Backup requires at least 12 GiB free on both nodes.' >&2; exit 1; }
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
staging="$(mktemp -d "$BACKUP_ROOT/.staging-$stamp.XXXXXX")"
lock_pid=''
cleanup() {
  status=$?
  trap - EXIT
  if [[ -n "$lock_pid" ]]; then
    exec 8>&-
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

# Collect the peer's small recovery configuration as well. Never copy a live
# MySQL data directory or use a mutable replica as an independent backup.
peer "set -e; test -d '$PEER_DEPLOY_ROOT'; tar -C '$PEER_DEPLOY_ROOT' --exclude='./backups' --exclude='./.public-ip' -czf - ." >"$staging/recovery/linux1-deploy.tar.gz"
gzip -t "$staging/recovery/linux1-deploy.tar.gz"
tar -C "$PROJECT_ROOT/deploy" --exclude='./backups' --exclude='./.public-ip' -czf "$staging/recovery/linux2-deploy.tar.gz" .

# Runtime roles/epochs/auth live outside the deployment tree. These plaintext
# archives remain in the private staging directory and are encrypted below.
python3 "$PROJECT_ROOT/deploy/redis/export-durable-state.py" master >"$staging/recovery/linux2-redis-state.tar.gz"
peer "python3 '$PEER_DEPLOY_ROOT/redis/export-durable-state.py' slave" >"$staging/recovery/linux1-redis-state.tar.gz"

set -a
. "$PROJECT_ROOT/deploy/.env"
set +a
: "${DB_ROOT_PASS:?}" "${DB_NAME:?}"
[[ "$DB_NAME" =~ ^[a-zA-Z0-9_]+$ ]] || exit 2
export MYSQL_PWD="$DB_ROOT_PASS"
mysql_args=(-uroot -h127.0.0.1 -P "${MYSQL_PORT:-3306}")
# Keep a separate connection's instance backup lock during the logical dump.
# It blocks conflicting DDL but permits ordinary transactional business writes.
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
[[ "$lock_ready" == true ]] || { echo 'Could not acquire the MySQL DDL backup lock.' >&2; exit 1; }
non_transactional="$(mysql "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_type='BASE TABLE' AND engine <> 'InnoDB'")"
[[ "$non_transactional" == 0 ]] || { echo 'Nontransactional tables require a separate consistent backup strategy.' >&2; exit 1; }
mysql "${mysql_args[@]}" -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB_NAME' AND table_type='BASE TABLE' ORDER BY table_name" >"$staging/recovery/tables.txt"
mysqldump "${mysql_args[@]}" --single-transaction --quick --skip-lock-tables --set-gtid-purged=OFF \
  --routines --events --triggers --hex-blob --no-tablespaces "$DB_NAME" | gzip -1 >"$staging/recovery/wao.sql.gz"
kill -0 "$lock_pid" # A dropped lock connection invalidates this recovery point.
printf 'UNLOCK INSTANCE;\n' >&8
exec 8>&-
wait "$lock_pid"
lock_pid=''
unset MYSQL_PWD DB_ROOT_PASS
gzip -t "$staging/recovery/wao.sql.gz"

# Reuse a completed controller archive; do not stop Jenkins or JumpServer.
jenkins_root="${JENKINS_BACKUP_ROOT:-/var/backups/jenkins}"
jenkins_archive="$(find "$jenkins_root" -maxdepth 1 -name 'jenkins-home-*.tar.gz' -mmin -1560 -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
[[ -n "$jenkins_archive" && -f "$jenkins_archive.sha256" ]] || { echo 'No verified Jenkins archive younger than 26 hours.' >&2; exit 1; }
(cd "$jenkins_root"; sha256sum -c "$(basename "$jenkins_archive.sha256")")
cp "$jenkins_archive" "$staging/recovery/jenkins-home.tar.gz"
printf 'format=2\ncreated_utc=%s\ndatabase=%s\nsource=linux2-primary\n' "$stamp" "$DB_NAME" >"$staging/recovery/metadata.txt"
(cd "$staging/recovery"; sha256sum *.gz tables.txt metadata.txt >SHA256SUMS)
tar -C "$staging" -cf - recovery | gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "$PASSPHRASE_FILE" --symmetric --cipher-algo AES256 --compress-algo none \
  --output "$staging/publish/recovery.tar.gpg"
(cd "$staging/publish"; sha256sum recovery.tar.gpg >SHA256SUMS)
printf '%s\n' "$stamp" >"$staging/publish/COMPLETE"

# Verify the remote bytes before publishing the immutable snapshot directory.
# No rsync --delete: a deletion at the source never removes historical backups.
peer "set -e; umask 077; mkdir -p '$PEER_ROOT'; test ! -e '$PEER_ROOT/$stamp'; mkdir '$PEER_ROOT/.incoming-$stamp'"
tar -C "$staging/publish" -cf - recovery.tar.gpg SHA256SUMS COMPLETE | peer "tar -C '$PEER_ROOT/.incoming-$stamp' -xf -"
peer "set -e; cd '$PEER_ROOT/.incoming-$stamp'; sha256sum -c SHA256SUMS; mv '$PEER_ROOT/.incoming-$stamp' '$PEER_ROOT/$stamp'"
mv "$staging/publish" "$BACKUP_ROOT/$stamp"
printf '%s\n' "$stamp" >"$BACKUP_ROOT/last-success.tmp"
mv "$BACKUP_ROOT/last-success.tmp" "$BACKUP_ROOT/last-success"
echo "Verified peer backup: $stamp"
# Only after a new copy has been verified, prune completed historical copies.
# A failed transfer never removes the previous recovery point.
retention_script="$(dirname "$0")/peer-retention.py"
python3 "$retention_script" "$BACKUP_ROOT" --apply
peer "python3 - '$PEER_ROOT' --apply" <"$retention_script"
