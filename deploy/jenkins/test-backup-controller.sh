#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test_root="$(mktemp -d)"
cleanup() { rm -rf -- "$test_root"; }
trap cleanup EXIT HUP INT TERM
mkdir -p "$test_root/bin"
cat >"$test_root/bin/curl" <<'SH'
#!/bin/sh
exit 22
SH
chmod 0755 "$test_root/bin/curl"

PATH="$test_root/bin:$PATH" sh "$script_dir/backup-controller.sh"
if PATH="$test_root/bin:$PATH" sh "$script_dir/backup-controller.sh" --require-created; then
  echo 'Required Jenkins backup unexpectedly accepted an unavailable controller.' >&2
  exit 1
fi
if PATH="$test_root/bin:$PATH" sh "$script_dir/backup-controller.sh" --invalid; then
  echo 'Invalid Jenkins backup option was unexpectedly accepted.' >&2
  exit 1
fi

printf '%s\n' 'Jenkins optional and required backup skip policies passed.'
