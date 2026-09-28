#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
snapshot="${1:?Usage: verify-nacos-recovery.sh SNAPSHOT PASSPHRASE_FILE}"
passphrase="${2:?Missing passphrase file}"
credential_container="${3:-wao-slave-wao-server-1}"
[[ -f "$snapshot/COMPLETE" && -s "$passphrase" ]]
(cd "$snapshot"; sha256sum -c SHA256SUMS)
stage="$(mktemp -d "${TMPDIR:-/tmp}/wao-nacos-restore.XXXXXX")"
container="wao-nacos-restore-$(date -u +%Y%m%d%H%M%S)-$$"
image=''
created=false
loaded=false
cleanup() {
  status=$?
  trap - EXIT
  if [[ "$created" == true ]]; then docker rm -f "$container" >/dev/null; fi
  if [[ "$loaded" == true ]]; then docker image rm "$image" >/dev/null; fi
  rm -rf -- "$stage"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
trap 'echo "Nacos restore failed at line $LINENO" >&2' ERR
gpg --batch --pinentry-mode loopback --passphrase-file "$passphrase" --decrypt "$snapshot/recovery.tar.gpg" |
  tar -C "$stage" -xf -
image="$(sed -n 's/^image=//p' "$stage/metadata.txt")"
[[ "$image" =~ ^wao-nacos-recovery:[0-9]{8}T[0-9]{6}Z$ ]]
grep -qx 'consistency=clean-stopped-container' "$stage/metadata.txt"
if docker image inspect "$image" >/dev/null 2>&1; then
  echo 'Recovery image already exists; refusing to overwrite it.' >&2; exit 1
fi
docker load -i "$stage/image.tar.gz" >"$stage/load.log"
loaded=true
actual="$(docker image inspect -f '{{.Id}}' "$image")"
# Older Docker CLI versions can print a deprecation warning to stdout before ID.
expected="$(grep -E '^sha256:[0-9a-f]{64}$' "$stage/image-id")"
[[ "$expected" =~ ^sha256:[0-9a-f]{64}$ ]]
[[ "$actual" == "$expected" ]] || { echo "Image digest mismatch: $actual / $expected" >&2; exit 1; }
docker create --name "$container" --network none --memory 1536m --cpus 1 --pids-limit 512 \
  --env NACOS_SERVER_IP=127.0.0.1 --env JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 "$image" >/dev/null
created=true
docker start "$container" >/dev/null
ready=false
for attempt in $(seq 1 90); do
  if docker exec "$container" curl -fsS --max-time 2 http://127.0.0.1:8848/nacos/v1/console/health/readiness >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Isolated Nacos did not become ready.' >&2; exit 1; }
python3 "$(dirname "$0")/nacos-recovery-inventory.py" --container "$container" \
  --credential-container "$credential_container" >"$stage/restored-inventory.json"
cmp "$stage/inventory.json" "$stage/restored-inventory.json"
printf 'Restored inventory: '
cat "$stage/restored-inventory.json"
echo 'Nacos recovery verified: authenticated namespace/config content match, network none, no published ports.'
