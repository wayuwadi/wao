#!/usr/bin/env bash
# Restore an uploaded peer snapshot into an empty, isolated directory only.
set -Eeuo pipefail
config_dir="${WAO_BACKUP_CONFIG_DIR:-/etc/wao-backup}"
webdav_config="$config_dir/webdav.env"
cloud_passphrase="$config_dir/encryption-passphrase"
webdav_client="${WAO_BACKUP_WEBDAV_CLIENT:-/usr/local/lib/wao-backup/webdav_client.py}"
node_name="${WAO_BACKUP_NODE_NAME:-linux2}"
[[ -f "$webdav_config" && -s "$cloud_passphrase" ]] || exit 1
remote_root="$(sed -n 's/^WAO_BACKUP_REMOTE_ROOT=//p' "$webdav_config" | tail -n 1)"
remote_root="${remote_root:-WAO-backups}"
remote_node="${remote_root#/}/$node_name"
webdav=(python3 "$webdav_client" --config "$webdav_config")
[[ "${WAO_BACKUP_ALLOW_INSECURE_HTTP:-false}" == true ]] && webdav+=(--allow-http)
if [[ "${1:-}" == --list ]]; then
  exec "${webdav[@]}" list-dirs "$remote_node"
fi
stamp="${1:?Usage: restore-peer-offsite.sh SNAPSHOT EMPTY_DESTINATION or --list}"
destination="${2:?An empty destination directory is required}"
[[ "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || exit 2
[[ "$destination" == /* && "$destination" != / && ! -L "$destination" ]] || exit 2
[[ "$destination" == "$(realpath -m -- "$destination")" ]] || exit 2
if [[ -e "$destination" ]]; then
  [[ -d "$destination" && -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ]] || exit 2
else
  install -d -m 0700 "$destination"
fi
umask 077
staging="$(mktemp -d "${destination}.download.XXXXXX")"
trap 'rm -rf -- "$staging"' EXIT
remote="$remote_node/$stamp"
"${webdav[@]}" download "$remote/manifest.json" "$staging/manifest.json"
python3 - "$staging/manifest.json" "$stamp" <<'PY' >"$staging/SHA256SUMS-cloud"
import json, re, sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
expected = {'recovery.tar.gpg', 'SHA256SUMS', 'COMPLETE', 'recovery-point.json', 'peer-passphrase.gpg'}
if manifest.get('formatVersion') != 2 or manifest.get('snapshot') != sys.argv[2]:
    raise SystemExit('Unexpected offsite snapshot format or timestamp')
files = manifest.get('files')
if not isinstance(files, list) or {item.get('name') for item in files} != expected or len(files) != len(expected):
    raise SystemExit('Offsite snapshot file list is incomplete')
for item in files:
    if not re.fullmatch('[0-9a-f]{64}', item.get('sha256', '')) or not isinstance(item.get('bytes'), int) or item['bytes'] < 1:
        raise SystemExit('Invalid offsite snapshot digest or size')
    print(f"{item['sha256']}  {item['name']}")
PY
while read -r _ name; do
  "${webdav[@]}" download "$remote/$name" "$staging/$name"
done <"$staging/SHA256SUMS-cloud"
(cd "$staging" && sha256sum -c SHA256SUMS-cloud && sha256sum -c SHA256SUMS)
[[ "$(cat "$staging/COMPLETE")" == "$stamp" ]] || exit 1
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$cloud_passphrase" \
  --decrypt --output "$staging/peer-passphrase" "$staging/peer-passphrase.gpg"
[[ -s "$staging/peer-passphrase" ]] || exit 1
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$staging/peer-passphrase" \
  --decrypt "$staging/recovery.tar.gpg" | tar -tf - >/dev/null
gpg --batch --yes --pinentry-mode loopback --passphrase-file "$staging/peer-passphrase" \
  --decrypt "$staging/recovery.tar.gpg" | tar -xf - -C "$destination"
printf 'Offsite snapshot %s verified and extracted to %s; production was not modified.\n' \
  "$stamp" "$destination"
