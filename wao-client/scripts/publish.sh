#!/usr/bin/env bash
set -euo pipefail
incoming=$1
version=$2
[[ "$incoming" =~ ^/tmp/wao-client-[0-9]+-[0-9]+$ ]] || exit 2
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || exit 2
destination=${WAO_CLIENT_PUBLISH_ROOT:-/var/www/wao-downloads/desktop/client}
install -d -m 0755 "$destination"
exec 9>"$destination/.publish.lock"
flock -n 9 || { echo 'Another client publication is running'; exit 1; }
(cd "$incoming" && sha256sum -c "SHA256-${version}.txt")
# Validate all immutable files before replacing any live file.
for file in "$incoming"/*; do
  name=${file##*/}
  case "$name" in latest.json|WAO-windows-x64-setup.exe|WAO-macos-universal.dmg) continue ;; esac
  if [ -e "$destination/$name" ]; then
    cmp -s "$file" "$destination/$name" || { echo 'Version has different bytes; increment the version'; exit 1; }
  fi
done
cmp -s "$incoming/WAO_${version}_windows-x64-setup.exe" "$incoming/WAO-windows-x64-setup.exe"
cmp -s "$incoming/WAO_${version}_macos-universal.dmg" "$incoming/WAO-macos-universal.dmg"
if [ -f "$destination/latest.json" ]; then
  python3 - "$destination/latest.json" "$version" "$incoming/latest.json" <<'PY'
import json, sys
previous = json.load(open(sys.argv[1]))
old = tuple(map(int, previous['version'].split('.')))
new = tuple(map(int, sys.argv[2].split('.')))
if new < old: raise SystemExit('Refusing release downgrade')
candidate = json.load(open(sys.argv[3]))
if new == old and any(previous.get(key) != candidate.get(key) for key in ['platforms', 'downloads']):
    raise SystemExit('Refusing signature or artifact replacement for an existing release')
PY
fi
for file in "$incoming"/*; do
  name=${file##*/}
  [ "$name" = latest.json ] && continue
  install -m 0644 "$file" "$destination/$name.next"
  mv "$destination/$name.next" "$destination/$name"
done
install -m 0644 "$incoming/latest.json" "$destination/latest.json.next"
mv "$destination/latest.json.next" "$destination/latest.json"
rm -rf -- "$incoming"
