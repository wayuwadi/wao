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
test ! -e "$destination/WAO_${version}_windows-x64-setup.exe" || { echo 'Version already published; increment the version'; exit 1; }
if [ -f "$destination/latest.json" ]; then
  python3 - "$destination/latest.json" "$version" <<'PY'
import json, sys
old = tuple(map(int, json.load(open(sys.argv[1]))['version'].split('.')))
new = tuple(map(int, sys.argv[2].split('.')))
if new <= old: raise SystemExit('Refusing release downgrade or overwrite')
PY
fi
(cd "$incoming" && sha256sum -c "SHA256-${version}.txt")
for file in "$incoming"/*; do
  name=${file##*/}
  [ "$name" = latest.json ] && continue
  install -m 0644 "$file" "$destination/$name.next"
  mv "$destination/$name.next" "$destination/$name"
done
install -m 0644 "$incoming/latest.json" "$destination/latest.json.next"
mv "$destination/latest.json.next" "$destination/latest.json"
rm -rf -- "$incoming"
