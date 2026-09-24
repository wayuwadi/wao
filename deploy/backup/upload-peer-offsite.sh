#!/usr/bin/env bash
# Send the already verified peer recovery point to WebDAV without building a
# second multi-gigabyte local archive.
set -Eeuo pipefail
umask 077

config_dir="${WAO_BACKUP_CONFIG_DIR:-/etc/wao-backup}"
webdav_config="$config_dir/webdav.env"
cloud_passphrase="$config_dir/encryption-passphrase"
peer_config="${WAO_PEER_BACKUP_CONFIG:-$config_dir/peer.env}"
webdav_client="${WAO_BACKUP_WEBDAV_CLIENT:-/usr/local/lib/wao-backup/webdav_client.py}"
node_name="${WAO_BACKUP_NODE_NAME:-linux2}"
remote_retention_days="${WAO_BACKUP_REMOTE_RETENTION_DAYS:-30}"

[[ "$(id -u)" -eq 0 || "${WAO_BACKUP_TEST_MODE:-false}" == true ]] || exit 1
[[ -f "$webdav_config" && -s "$cloud_passphrase" && -f "$peer_config" ]] || {
  echo 'Offsite or peer backup credentials are not configured.' >&2; exit 1;
}
[[ "$remote_retention_days" =~ ^[0-9]+$ ]] && (( remote_retention_days >= 7 )) || exit 2
# Root-owned peer configuration is already used by the local backup service.
. "$peer_config"
: "${BACKUP_ROOT:?}" "${PASSPHRASE_FILE:?}"
[[ "$BACKUP_ROOT" == /* && "$BACKUP_ROOT" != / && -s "$PASSPHRASE_FILE" ]] || exit 2
stamp="$(cat "$BACKUP_ROOT/last-success")"
[[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || exit 2
snapshot="$BACKUP_ROOT/$stamp"
[[ ! -L "$snapshot" && -d "$snapshot" && -f "$snapshot/recovery.tar.gpg" ]] || exit 1
[[ "$(cat "$snapshot/COMPLETE")" == "$stamp" ]] || exit 1
(cd "$snapshot" && sha256sum -c SHA256SUMS)
python3 - "$stamp" <<'PY'
import datetime as dt, sys
from zoneinfo import ZoneInfo
created = dt.datetime.strptime(sys.argv[1], '%Y%m%dT%H%M%SZ').replace(tzinfo=dt.timezone.utc)
age = dt.datetime.now(dt.timezone.utc) - created
if not dt.timedelta(0) <= age < dt.timedelta(hours=30):
    raise SystemExit('No verified peer snapshot from the last 30 hours')
if created.astimezone(ZoneInfo('Asia/Shanghai')).date() != dt.datetime.now(ZoneInfo('Asia/Shanghai')).date():
    raise SystemExit('No verified peer snapshot from today')
PY

remote_root="$(sed -n 's/^WAO_BACKUP_REMOTE_ROOT=//p' "$webdav_config" | tail -n 1)"
remote_root="${remote_root:-WAO-backups}"
remote_root="${remote_root#/}"
remote_root="${remote_root%/}"
[[ "$remote_root" =~ ^[A-Za-z0-9._/-]+$ ]] || exit 2
remote_node="$remote_root/$node_name"
remote_snapshot="$remote_node/$stamp"
webdav=(python3 "$webdav_client" --config "$webdav_config")
[[ "${WAO_BACKUP_ALLOW_INSECURE_HTTP:-false}" == true ]] && webdav+=(--allow-http)
if [[ "$("${webdav[@]}" stat "$remote_snapshot/manifest.json")" != None ]]; then
  printf 'Peer snapshot already uploaded to WebDAV: %s\n' "$stamp"
  exit 0
fi

staging="$(mktemp -d "$BACKUP_ROOT/.offsite-$stamp.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$cloud_passphrase" \
  --symmetric --cipher-algo AES256 --compress-algo none \
  --output "$staging/peer-passphrase.gpg" "$PASSPHRASE_FILE"

python3 - "$snapshot" "$staging" "$stamp" "$node_name" <<'PY'
import hashlib, json, pathlib, sys
source, staging = map(pathlib.Path, sys.argv[1:3])
files = [source / name for name in ('recovery.tar.gpg', 'SHA256SUMS', 'COMPLETE', 'recovery-point.json')]
files.append(staging / 'peer-passphrase.gpg')
parts = []
for path in files:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    parts.append({'name': path.name, 'bytes': path.stat().st_size, 'sha256': digest.hexdigest()})
manifest = {'formatVersion': 2, 'snapshot': sys.argv[3], 'node': sys.argv[4],
            'source': 'verified Linux peer backup', 'files': parts}
(staging / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
PY

"${webdav[@]}" ensure-dir "$remote_snapshot"
for name in recovery.tar.gpg SHA256SUMS COMPLETE recovery-point.json; do
  "${webdav[@]}" upload "$snapshot/$name" "$remote_snapshot/$name"
done
"${webdav[@]}" upload "$staging/peer-passphrase.gpg" "$remote_snapshot/peer-passphrase.gpg" --force
# The manifest is the commit marker; incomplete uploads have no manifest.
"${webdav[@]}" upload "$staging/manifest.json" "$remote_snapshot/manifest.json" --force

cutoff="$(date -u -d "$remote_retention_days days ago" +%Y%m%dT%H%M%SZ)"
while IFS= read -r previous; do
  [[ "$previous" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || continue
  if [[ "$previous" < "$cutoff" ]]; then
    "${webdav[@]}" delete "$remote_node/$previous"
  fi
done < <("${webdav[@]}" list-dirs "$remote_node")
printf 'Verified peer snapshot uploaded to WebDAV: %s\n' "$stamp"
