#!/bin/bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
root="$(mktemp -d)"
trap 'rm -rf -- "$root"' EXIT
mkdir -p "$root/config" "$root/peer" "$root/remote" "$root/payload/recovery/database"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$root/peer/$stamp"
printf 'CREATE TABLE note (id INT);\n' | gzip >"$root/payload/recovery/database/wao.sql.gz"
printf '%s' 'peer-test-passphrase-123' >"$root/config/peer-passphrase"
printf '%s' 'cloud-test-passphrase-456' >"$root/config/encryption-passphrase"
tar -C "$root/payload" -cf - recovery | gpg --batch --yes --pinentry-mode loopback \
  --passphrase-file "$root/config/peer-passphrase" --symmetric --cipher-algo AES256 \
  --output "$root/peer/$stamp/recovery.tar.gpg"
printf '%s\n' "$stamp" >"$root/peer/$stamp/COMPLETE"
printf '%s\n' '{"file":"mysql-bin.000001","position":1}' >"$root/peer/$stamp/recovery-point.json"
(cd "$root/peer/$stamp" && sha256sum recovery.tar.gpg recovery-point.json >SHA256SUMS)
printf '%s\n' "$stamp" >"$root/peer/last-success"
printf 'BACKUP_ROOT=%s\nPASSPHRASE_FILE=%s\n' \
  "$root/peer" "$root/config/peer-passphrase" >"$root/config/peer.env"
printf '%s\n' 'WAO_BACKUP_WEBDAV_URL=http://127.0.0.1/webdav' \
  'WAO_BACKUP_WEBDAV_USER=test' 'WAO_BACKUP_WEBDAV_PASSWORD=test' \
  'WAO_BACKUP_REMOTE_ROOT=WAO-backups' >"$root/config/webdav.env"
cat >"$root/fake-webdav.py" <<'PY'
import os, pathlib, shutil, sys
args = sys.argv[1:]
assert args[:2] == ['--config', os.environ['WAO_BACKUP_CONFIG_DIR'] + '/webdav.env']
args = args[2:]
if args[0] == '--allow-http': args = args[1:]
command, *values = args
if values and values[-1] == '--force': values.pop()
root = pathlib.Path(os.environ['WAO_TEST_REMOTE_ROOT'])
if command == 'ensure-dir':
    (root / values[0]).mkdir(parents=True, exist_ok=True)
elif command == 'upload':
    target = root / values[1]
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(values[0], target)
elif command == 'download':
    shutil.copy2(root / values[0], values[1])
elif command == 'stat':
    path = root / values[0]
    print(path.stat().st_size if path.exists() else 'None')
elif command == 'list-dirs':
    path = root / values[0]
    if path.is_dir():
        print('\n'.join(sorted(child.name for child in path.iterdir() if child.is_dir())))
elif command == 'delete':
    shutil.rmtree(root / values[0], ignore_errors=True)
else:
    raise SystemExit(command)
PY
export WAO_BACKUP_TEST_MODE=true WAO_BACKUP_CONFIG_DIR="$root/config"
export WAO_BACKUP_WEBDAV_CLIENT="$root/fake-webdav.py" WAO_TEST_REMOTE_ROOT="$root/remote"
export WAO_BACKUP_ALLOW_INSECURE_HTTP=true
bash "$repo_root/deploy/backup/upload-peer-offsite.sh"
test -f "$root/remote/WAO-backups/linux2/$stamp/manifest.json"
test ! -d "$root/peer/.offsite-$stamp"
bash "$repo_root/deploy/backup/restore-peer-offsite.sh" "$stamp" "$root/restored"
gzip -dc "$root/restored/recovery/database/wao.sql.gz" | grep -Fq 'CREATE TABLE note'
printf 'Corruption' >>"$root/remote/WAO-backups/linux2/$stamp/recovery.tar.gpg"
if bash "$repo_root/deploy/backup/restore-peer-offsite.sh" "$stamp" "$root/corrupt" >/dev/null 2>&1; then
  echo 'Corrupt offsite archive was accepted' >&2
  exit 1
fi
echo 'Peer snapshot upload, isolated restore, and checksum rejection passed.'
