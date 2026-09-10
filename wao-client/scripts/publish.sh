#!/usr/bin/env bash
set -euo pipefail
incoming=$1
version=$2
[[ "$incoming" =~ ^/tmp/wao-client-[0-9]+-[0-9]+$ ]] || exit 2
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || exit 2
destination=/var/www/wao-downloads/desktop/client
install -d -m 0755 "$destination"
exec 9>"$destination/.publish.lock"
flock -n 9 || { echo 'Another client publication is running'; exit 1; }
(cd "$incoming" && sha256sum -c "SHA256-${version}.txt")
# A retry may complete an interrupted upload, but can never replace versioned bytes.
if [ -e "$destination/WAO_${version}_windows-x64-setup.exe" ]; then
  cmp -s "$incoming/WAO_${version}_windows-x64-setup.exe" "$destination/WAO_${version}_windows-x64-setup.exe" || { echo 'Version has different bytes; increment the version'; exit 1; }
fi
if [ -f "$destination/latest.json" ]; then
  python3 - "$destination/latest.json" "$version" "$incoming/latest.json" <<'PY'
import json, sys
previous = json.load(open(sys.argv[1]))
old = tuple(map(int, previous['version'].split('.')))
new = tuple(map(int, sys.argv[2].split('.')))
if new < old: raise SystemExit('Refusing release downgrade')
if new == old and previous['platforms'] != json.load(open(sys.argv[3]))['platforms']:
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
