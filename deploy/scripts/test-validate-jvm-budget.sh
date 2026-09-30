#!/bin/sh
set -eu
root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/deploy/scripts" "$tmp/bin"
cp "$root/deploy/scripts/validate-deployment-node.sh" \
  "$root/deploy/scripts/image-content.sh" "$tmp/deploy/scripts/"
touch "$tmp/deploy/.env" "$tmp/deploy/docker-compose-master.yml"
cat > "$tmp/bin/docker" <<'SH'
#!/bin/sh
case "$1" in
  info) exit 0 ;;
  compose) for arg in "$@"; do last="$arg"; done; echo "$last" ;;
  image) echo 'linux/amd64 {"Config":{"Env":["PATH=/usr/local/bin"]}} ["sha256:layer"]' ;;
  inspect)
    case "$3" in
      *Config.Image*) echo "$4:test" ;;
      *) echo healthy ;;
    esac ;;
  exec)
    if [ "$3" = sh ]; then
      if [ "$2" = wao-server ]; then echo "${TEST_SERVER_HEAP-2147483648}"
      else echo "${TEST_OTHER_HEAP-536870912}"; fi
    else echo '{"code":0,"data":{"protocol":1}}'; fi ;;
  *) exit 2 ;;
esac
SH
chmod +x "$tmp/bin/docker"
export PATH="$tmp/bin:$PATH"
sh "$tmp/deploy/scripts/validate-deployment-node.sh" master test > "$tmp/result"
grep -q 'wao-server_max_heap_bytes=2147483648' "$tmp/result"
for invalid in 24461180928 1073741824 ''; do
  if TEST_SERVER_HEAP="$invalid" sh "$tmp/deploy/scripts/validate-deployment-node.sh" master test > "$tmp/result" 2>&1; then
    echo "Unsafe or unknown application heap accepted: $invalid" >&2; exit 1
  fi
  grep -q 'JVM max heap' "$tmp/result"
done
if TEST_OTHER_HEAP=1073741824 sh "$tmp/deploy/scripts/validate-deployment-node.sh" master test > "$tmp/result" 2>&1; then
  echo 'Unsafe agent/gateway heap accepted' >&2; exit 1
fi
grep -q 'JVM max heap' "$tmp/result"
echo 'JVM deployment budget validation passed'
