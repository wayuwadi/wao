#!/usr/bin/env bash
set -Eeuo pipefail

config_dir="${WAO_BACKUP_CONFIG_DIR:-/etc/wao-backup}"
webdav_config="${config_dir}/webdav.env"
passphrase_file="${config_dir}/encryption-passphrase"
webdav_client="${WAO_BACKUP_WEBDAV_CLIENT:-/usr/local/lib/wao-backup/webdav_client.py}"
node_name="${WAO_BACKUP_NODE_NAME:-linux2}"

[[ -f "$webdav_config" && -f "$passphrase_file" ]] || {
  echo 'Offsite backup credentials are not configured.' >&2
  exit 1
}
remote_root="$(sed -n 's/^WAO_BACKUP_REMOTE_ROOT=//p' "$webdav_config" | tail -n 1)"
remote_root="${remote_root:-WAO-backups}"
remote_node="${remote_root#/}/$node_name"
webdav=(python3 "$webdav_client" --config "$webdav_config")
[[ "${WAO_BACKUP_ALLOW_INSECURE_HTTP:-false}" == true ]] && webdav+=(--allow-http)

if [[ "${1:-}" == --list ]]; then
  exec "${webdav[@]}" list-dirs "$remote_node"
fi

snapshot="${1:?Usage: restore-offsite-backup.sh SNAPSHOT EMPTY_DESTINATION or --list}"
destination="${2:?An empty destination directory is required}"
[[ "$snapshot" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo 'Invalid snapshot name.' >&2; exit 2; }
[[ "$destination" == /* && "$destination" != / ]] || { echo 'Destination must be a safe absolute path.' >&2; exit 2; }
[[ "$destination" == "$(realpath -m -- "$destination")" ]] || {
  echo 'Destination must be canonical and cannot traverse a symlink.' >&2
  exit 2
}
[[ ! -L "$destination" ]] || { echo 'Destination cannot be a symbolic link.' >&2; exit 2; }
if [[ -e "$destination" ]]; then
  [[ -d "$destination" && -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ]] || {
    echo 'Destination already exists and is not empty.' >&2
    exit 2
  }
else
  install -d -m 0700 "$destination"
fi

umask 077
work_dir="$(mktemp -d "${destination}.download.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT HUP INT TERM
remote_snapshot="$remote_node/$snapshot"
"${webdav[@]}" download "$remote_snapshot/manifest.json" "$work_dir/manifest.json"

python3 - "$work_dir/manifest.json" <<'PY' >"$work_dir/parts"
import json
import re
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if manifest.get("formatVersion") != 1:
    raise SystemExit("Unsupported backup format")
parts = manifest.get("archiveParts")
if not isinstance(parts, list) or not parts:
    raise SystemExit("Backup manifest has no archive parts")
for part in parts:
    name = part.get("name", "")
    digest = part.get("sha256", "")
    if not re.fullmatch(r"wao-linux2-[0-9]{8}T[0-9]{6}Z\.tar\.gz\.gpg\.part-[0-9]{4}", name):
        raise SystemExit("Unsafe archive part name")
    if not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise SystemExit("Invalid archive part digest")
    print(f"{digest}  {name}")
PY

while read -r _ part; do
  "${webdav[@]}" download "$remote_snapshot/$part" "$work_dir/$part"
done <"$work_dir/parts"
(cd "$work_dir" && sha256sum -c parts)
cat "$work_dir"/*.part-* \
  | gpg --batch --yes --pinentry-mode loopback \
      --passphrase-file "$passphrase_file" --decrypt \
  >"$work_dir/backup.tar.gz"
gzip -t "$work_dir/backup.tar.gz"
tar -tzf "$work_dir/backup.tar.gz" >/dev/null
tar -xzf "$work_dir/backup.tar.gz" -C "$destination"
printf 'Backup %s was verified and extracted to %s; production was not modified.\n' \
  "$snapshot" "$destination"
