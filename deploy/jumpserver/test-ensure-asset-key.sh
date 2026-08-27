#!/bin/sh
set -eu

project_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT HUP INT TERM
fake_bin="$work_dir/bin"
state_root="$work_dir/state"
asset_private_key="$state_root/data/core/data/bootstrap/wao-assets"
asset_public_key="$asset_private_key.pub"
mkdir -p "$fake_bin" "$(dirname "$asset_private_key")"

cat >"$fake_bin/sudo" <<'EOF'
#!/bin/sh
set -eu
test "${1:-}" = -n && shift
if [ "${1:-}" = -u ]; then shift 2; fi
exec "$@"
EOF
cat >"$fake_bin/install" <<'EOF'
#!/bin/sh
set -eu
mode=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    -m) mode="$2"; shift 2 ;;
    -o|-g) shift 2 ;;
    *) break ;;
  esac
done
exec /usr/bin/install -m "$mode" "$@"
EOF
chmod +x "$fake_bin/sudo" "$fake_bin/install"

# Reproduce the interrupted first run that caused ssh-keygen to prompt in Jenkins.
printf 'stale private staging file\n' >"$state_root/wao-assets"
printf 'stale public staging file\n' >"$state_root/wao-assets.pub"

PATH="$fake_bin:$PATH" timeout 10 sh \
  "$project_root/deploy/jumpserver/ensure-asset-key.sh" \
  "$state_root" "$asset_private_key" "$asset_public_key"

test -s "$asset_private_key"
test -s "$asset_public_key"
derived_public_key="$(ssh-keygen -y -f "$asset_private_key" | awk '{print $1 " " $2}')"
published_public_key="$(awk '{print $1 " " $2}' "$asset_public_key")"
test "$derived_public_key" = "$published_public_key"
test "$(cat "$state_root/wao-assets")" = 'stale private staging file'
first_checksum="$(sha256sum "$asset_private_key" | awk '{print $1}')"

PATH="$fake_bin:$PATH" timeout 10 sh \
  "$project_root/deploy/jumpserver/ensure-asset-key.sh" \
  "$state_root" "$asset_private_key" "$asset_public_key"
test "$(sha256sum "$asset_private_key" | awk '{print $1}')" = "$first_checksum"

echo 'JumpServer asset key generation tests passed.'
