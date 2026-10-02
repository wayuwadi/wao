#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
export RETIRE_LOG="$scratch/calls"
cat >"$scratch/systemctl" <<'EOF'
#!/bin/sh
test "$1" = cat || echo "$*" >>"$RETIRE_LOG"
EOF
printf '#!/bin/sh\nexit 1\n' >"$scratch/id"
chmod +x "$scratch/systemctl" "$scratch/id"
PATH="$scratch:$PATH" sh "$root/retire-linux.sh"
printf '%s\n' 'disable --now wao-ai-runner.service' >"$scratch/expected"
diff -u "$scratch/expected" "$RETIRE_LOG"
echo 'Only the WAO Runner service was disabled; no files were deleted.'
