#!/usr/bin/env bash
# Linux2 operator tool: WAO-only source snapshot, encrypted before publication.
set -Eeuo pipefail
umask 077
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
config="${WAO_PEER_BACKUP_CONFIG:-/etc/wao-backup/peer.env}"
[[ -f "$config" ]] || { echo 'Missing private peer-backup configuration.' >&2; exit 1; }
. "$config"
: "${PROJECT_ROOT:?}" "${BACKUP_ROOT:?}" "${PEER_HOST:?}" "${SSH_KEY:?}" \
  "${KNOWN_HOSTS:?}" "${PASSPHRASE_FILE:?}"
[[ "$BACKUP_ROOT" == /* && "$BACKUP_ROOT" != / && "$BACKUP_ROOT" != *..* ]] || exit 2
[[ "$PEER_HOST" =~ ^[a-zA-Z0-9_.@-]+$ && "$PEER_HOST" != -* ]] || exit 2
[[ -s "$PASSPHRASE_FILE" && -f "$SSH_KEY" && -f "$KNOWN_HOSTS" ]] || exit 1
[[ "${PEER_HOST#*@}" == linux1.wayuwa.cn ]] || {
  echo 'Snapshot peer must be Linux1.' >&2; exit 1;
}
# The role check must use the current production deployment environment.
. "$PROJECT_ROOT/deploy/.env"
[[ "${WAO_MYSQL_PRIMARY:-}" == linux1 ]] || {
  echo 'Linux1 is not the declared WAO writer.' >&2; exit 1;
}
rate="${WAO_SNAPSHOT_RATE_BPS:-524288}"
[[ "$rate" =~ ^[0-9]+$ ]] && (( rate >= 65536 && rate <= 2097152 )) || exit 2

root="$BACKUP_ROOT/authoritative-wao"
install -d -m 0700 "$root"
exec 9>"$root/.lock"
flock -n 9 || { echo 'WAO writer snapshot already running.' >&2; exit 1; }
read -r total free < <(df -Pk "$root" | awk 'NR==2 { print $2, $4 }')
(( free - 12582912 >= total / 5 )) || {
  echo 'Snapshot requires 12 GiB working headroom and a 20% disk reserve.' >&2; exit 1;
}
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
staging="$(mktemp -d "$root/.staging-$stamp.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
final="$root/$stamp"
[[ ! -e "$final" ]] || exit 1

ssh_args=(-o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes
  -o ConnectTimeout=8 -o ServerAliveInterval=30 -o ServerAliveCountMax=3
  -o HostKeyAlias=linux1.wayuwa.cn -o "UserKnownHostsFile=$KNOWN_HOSTS" -i "$SSH_KEY")
endpoint="$(python3 /usr/local/lib/wao-network/refresh-mesh-endpoint.py --resolve linux1.wayuwa.cn)"
ssh_args+=(-o "HostName=$endpoint")

timeout 90m ssh "${ssh_args[@]}" "$PEER_HOST" \
  "WAO_SNAPSHOT_RATE_BPS=$rate bash -s" \
  <"$script_dir/export-authoritative-wao-on-writer.sh" >"$staging/wao.sql.gz"
gzip -t "$staging/wao.sql.gz"
python3 "$script_dir/inspect-authoritative-wao-snapshot.py" \
  "$staging/wao.sql.gz" >"$staging/recovery-point.json"
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$PASSPHRASE_FILE" \
  --symmetric --cipher-algo AES256 --compress-algo none \
  --output "$staging/wao.sql.gz.gpg" "$staging/wao.sql.gz"
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$PASSPHRASE_FILE" \
  --decrypt "$staging/wao.sql.gz.gpg" | gzip -t
rm "$staging/wao.sql.gz"
(cd "$staging" && sha256sum wao.sql.gz.gpg recovery-point.json >SHA256SUMS && sha256sum -c SHA256SUMS)
printf 'wao-only source=linux1 created_utc=%s\n' "$stamp" >"$staging/COMPLETE"
mv -- "$staging" "$final"
trap - EXIT
printf 'Verified encrypted WAO writer snapshot: %s\n' "$final"
