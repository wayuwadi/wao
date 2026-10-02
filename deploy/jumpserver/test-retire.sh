#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
export RETIRE_LOG="$scratch/calls"
cat >"$scratch/docker" <<'EOF'
#!/bin/sh
case "$1" in
  ps) printf 'owned\nunrelated\n' ;;
  inspect)
    case "$4" in
      owned) echo /opt/wao-jumpserver/installer-v4.10.19/compose ;;
      unrelated) echo /opt/other/compose ;;
    esac ;;
  *) echo "docker $*" >>"$RETIRE_LOG" ;;
esac
EOF
cat >"$scratch/systemctl" <<'EOF'
#!/bin/sh
test "$1" = cat || echo "systemctl $*" >>"$RETIRE_LOG"
EOF
chmod +x "$scratch/docker" "$scratch/systemctl"
PATH="$scratch:$PATH" sh "$root/retire.sh"
printf '%s\n' 'systemctl disable --now wao-jumpserver-tunnel.service' 'docker update --restart=no owned' 'docker stop owned' >"$scratch/expected"
diff -u "$scratch/expected" "$RETIRE_LOG"
echo 'JumpServer retirement preserves unrelated containers and does not delete data.'
