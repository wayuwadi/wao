#!/usr/bin/env bash
set -euo pipefail
incoming=${1:?Incoming directory required}
version=${2:?Version required}
[[ "$incoming" =~ ^/tmp/wao-mobile-[0-9]+-[0-9]+$ ]] || exit 2
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || exit 2
destination=/var/www/wao-downloads/mobile
install -d -m 0755 "$destination"
exec 9>"$destination/.publish.lock"
flock -n 9 || { echo 'Another mobile publication is running'; exit 1; }
python3 - "$incoming" "$destination" "$version" <<'PY'
import hashlib, json, pathlib, sys
incoming, destination = map(pathlib.Path, sys.argv[1:3])
version = sys.argv[3]
manifest = json.loads((incoming / 'latest.json').read_text())
assert manifest['version'] == version
assert set(manifest['files']) == {'android', 'ios'}
for platform, suffix in {'android': 'android.apk', 'ios': 'ios-unsigned.ipa'}.items():
    f = manifest['files'][platform]
    assert f['name'] == f'WAO-{version}-{suffix}'
    path = incoming / version / f['name']
    assert not path.is_symlink()
    data = path.read_bytes()
    assert len(data) == f['size'] and hashlib.sha256(data).hexdigest() == f['sha256']
latest = destination / 'latest.json'
if latest.exists():
    old = json.loads(latest.read_text())
    assert tuple(map(int, version.split('.'))) >= tuple(map(int, old['version'].split('.'))), 'Refusing downgrade'
    if old['version'] == version:
        assert old['files'] == manifest['files'], 'Refusing same-version replacement'
previous = destination / version
if previous.exists():
    for file in (incoming / version).iterdir():
        assert (previous / file.name).read_bytes() == file.read_bytes(), 'Immutable release differs'
PY
(cd "$incoming/$version" && sha256sum -c SHA256SUMS.txt)
if [ ! -d "$destination/$version" ]; then
  stage=$(mktemp -d "$destination/.incoming-${version}-XXXXXX")
  trap 'rm -rf -- "$stage"' EXIT
  install -m 0644 "$incoming/$version/"* "$stage/"
  chmod 0755 "$stage"
  mv "$stage" "$destination/$version"
  trap - EXIT
fi
install -m 0644 "$incoming/latest.json" "$destination/latest.json.next"
mv "$destination/latest.json.next" "$destination/latest.json"
rm -rf -- "$incoming"
