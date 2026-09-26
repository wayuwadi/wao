#!/bin/sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
test_root="$(mktemp -d)"
cleanup() { rm -rf -- "$test_root"; }
trap cleanup EXIT HUP INT TERM

project_root="$test_root/project"
jumpserver_root="$test_root/jumpserver"
jenkins_root="$test_root/jenkins"
config_root="$test_root/config"
local_root="$test_root/local"
remote_root="$test_root/remote"
mkdir -p \
  "$project_root/deploy/certs" "$project_root/deploy/backups" \
  "$jumpserver_root/config" "$jumpserver_root/data" \
  "$jenkins_root" "$config_root" "$local_root" "$remote_root"

printf '%s\n' \
  'DB_ROOT_PASS=test-root-password' \
  'DB_NAME=wao' \
  'MYSQL_HOST=127.0.0.1' >"$project_root/deploy/.env"
printf '%s\n' 'private=true' >"$project_root/deploy/.env.secrets"
printf '%s\n' 'certificate' >"$project_root/deploy/certs/gateway.p12"
printf '%s\n' 'must-not-be-included' >"$project_root/deploy/backups/old.sql"
printf '%s\n' 'jumpserver-config' >"$jumpserver_root/config/config.txt"
printf '%s\n' 'jumpserver-data' >"$jumpserver_root/data/asset"

jenkins_archive="$jenkins_root/jenkins-home-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
printf '%s\n' 'jenkins-state' | gzip -c >"$jenkins_archive"
(cd "$jenkins_root" && sha256sum "$(basename "$jenkins_archive")" >"$(basename "$jenkins_archive").sha256")

printf '%s\n' \
  'WAO_BACKUP_WEBDAV_URL=http://127.0.0.1/webdav' \
  'WAO_BACKUP_WEBDAV_USER=test' \
  'WAO_BACKUP_WEBDAV_PASSWORD=test' \
  'WAO_BACKUP_REMOTE_ROOT=WAO-backups' >"$config_root/webdav.env"
printf '%s' 'test-only-encryption-passphrase' >"$config_root/encryption-passphrase"

fake_dump="$test_root/mysqldump"
cat >"$fake_dump" <<'SH'
#!/bin/sh
printf '%s\n' 'CREATE TABLE backup_proof (id BIGINT PRIMARY KEY);'
SH
chmod 0755 "$fake_dump"

fake_webdav="$test_root/webdav-client"
cat >"$fake_webdav" <<'PY'
#!/usr/bin/env python3
import os
import shutil
import sys
from pathlib import Path

args = sys.argv[1:]
assert args[0] == "--config"
args = args[2:]
if args and args[0] == "--allow-http":
    args = args[1:]
command, *values = args
root = Path(os.environ["WAO_TEST_REMOTE_ROOT"])
if command == "ensure-dir":
    (root / values[0]).mkdir(parents=True, exist_ok=True)
elif command == "upload":
    destination = root / values[1]
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(values[0], destination)
elif command == "download":
    shutil.copy2(root / values[0], values[1])
elif command == "list-dirs":
    directory = root / values[0]
    if directory.is_dir():
        print("\n".join(sorted(path.name for path in directory.iterdir() if path.is_dir())))
elif command == "delete":
    shutil.rmtree(root / values[0], ignore_errors=True)
else:
    raise SystemExit(f"Unexpected fake WebDAV command: {command}")
PY
chmod 0755 "$fake_webdav"

export WAO_BACKUP_TEST_MODE=true
export WAO_BACKUP_SKIP_JENKINS_REFRESH=true
export WAO_BACKUP_SKIP_JUMPSERVER_CONTROL=true
export WAO_BACKUP_CONFIG_DIR="$config_root"
export WAO_BACKUP_PROJECT_ROOT="$project_root"
export WAO_BACKUP_JUMPSERVER_ROOT="$jumpserver_root"
export WAO_BACKUP_JENKINS_BACKUP_DIR="$jenkins_root"
export WAO_BACKUP_LOCAL_DIR="$local_root"
export WAO_BACKUP_WEBDAV_CLIENT="$fake_webdav"
export WAO_BACKUP_MYSQLDUMP_COMMAND="$fake_dump"
export WAO_BACKUP_CHUNK_BYTES=1048576
export WAO_BACKUP_ALLOW_INSECURE_HTTP=true
export WAO_TEST_REMOTE_ROOT="$remote_root"

bash "$repo_root/deploy/backup/create-offsite-backup.sh"
snapshot="$(find "$local_root" -mindepth 1 -maxdepth 1 -type d -name '????????T??????Z' -printf '%f\n')"
test -n "$snapshot"
test -f "$local_root/$snapshot/manifest.json"
test -f "$local_root/$snapshot/.uploaded"
test -f "$remote_root/WAO-backups/linux2/$snapshot/manifest.json"
python3 - "$local_root/$snapshot/manifest.json" <<'PY'
import json
import sys
from pathlib import Path

manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert manifest["formatVersion"] == 1
assert manifest["database"] == "wao"
assert manifest["archiveParts"]
PY

restore_root="$test_root/restored"
bash "$repo_root/deploy/backup/restore-offsite-backup.sh" "$snapshot" "$restore_root"
test -f "$restore_root/recovery/database/wao.sql.gz"
gzip -dc "$restore_root/recovery/database/wao.sql.gz" | grep -Fq 'CREATE TABLE backup_proof'
test -f "$restore_root/recovery/jenkins/$(basename "$jenkins_archive")"
test -f "$restore_root/${jumpserver_root#/}/data/asset"
test -f "$restore_root/${project_root#/}/deploy/.env.secrets"
test ! -e "$restore_root/${project_root#/}/deploy/backups/old.sql"

ln -s "$test_root" "$test_root/restore-link"
if bash "$repo_root/deploy/backup/restore-offsite-backup.sh" \
    "$snapshot" "$test_root/restore-link" >/dev/null 2>&1; then
  echo 'Restore unexpectedly accepted a symbolic-link destination.' >&2
  exit 1
fi

printf '%s\n' 'Offsite backup creation, upload commit marker and isolated restore passed.'
