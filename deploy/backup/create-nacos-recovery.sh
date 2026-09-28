#!/usr/bin/env bash
# Preserve the existing embedded-storage Nacos without upgrading or recreating it.
set -Eeuo pipefail
umask 077
. "${WAO_PEER_BACKUP_CONFIG:-/etc/wao-backup/peer.env}"
: "${BACKUP_ROOT:?}" "${PEER_ROOT:?}" "${PEER_HOST:?}" "${SSH_KEY:?}" "${KNOWN_HOSTS:?}" "${PASSPHRASE_FILE:?}"
[[ "$BACKUP_ROOT" =~ ^/[a-zA-Z0-9_/-]+$ && "$BACKUP_ROOT" != / ]] || exit 2
[[ "$PEER_ROOT" =~ ^/[a-zA-Z0-9_/-]+$ && "$PEER_ROOT" != / ]] || exit 2
[[ "$PEER_HOST" =~ ^[a-zA-Z0-9_.@-]+$ && "$PEER_HOST" != -* ]] || exit 2
container=nacos
root="${BACKUP_ROOT}-nacos"
remote="${PEER_ROOT}-nacos"
install -d -m 0700 "$root"
exec 9>"$root/.lock"
flock -n 9 || exit 1
exec 8>"${WAO_INFRA_LOCK_FILE:-/run/lock/wao-infra-maintenance.lock}"
flock -w 600 8 || { echo 'Application deployment is still active; backup deferred.' >&2; exit 1; }
[[ "$(docker inspect -f '{{.State.Running}} {{len .Mounts}}' "$container")" == 'true 0' ]] || {
  echo 'Expected running embedded Nacos with no mounted volumes; review topology first.' >&2; exit 1;
}
docker inspect "$container" | python3 -c '
import json,sys
c=json.load(sys.stdin)[0]
e=dict(x.split("=",1) for x in c["Config"]["Env"])
assert e.get("MODE")=="standalone", "Expected standalone Nacos"
assert e.get("SPRING_DATASOURCE_PLATFORM", "") in ("", "embedded"), "External storage requires a different backup"
'
docker exec "$container" curl -fsS --max-time 10 http://127.0.0.1:8848/nacos/v1/console/health/readiness >/dev/null
ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 -i "$SSH_KEY" -o "UserKnownHostsFile=$KNOWN_HOSTS")
peer() { ssh "${ssh_args[@]}" "$PEER_HOST" "$@"; }
(( $(df -Pk "$root" | awk 'NR==2 {print $4}') > 12582912 ))
(( $(peer "df -Pk /home | awk 'NR==2 {print \$4}'") > 12582912 ))
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
stage="$(mktemp -d "$root/.staging-$stamp.XXXXXX")"
tag="wao-nacos-recovery:$stamp"
guard="wao-nacos-restart-$stamp"
restart_needed=false
cleanup() {
  status=$?
  trap - EXIT
  if [[ "$restart_needed" == true ]]; then docker start "$container" >/dev/null || true; fi
  # Keep the independent restart watchdog if starting Nacos failed.
  if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" == true ]]; then
    systemctl stop "$guard.timer" 2>/dev/null || true
  fi
  docker image rm "$tag" >/dev/null 2>&1 || true
  rm -rf -- "$stage"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
docker inspect "$container" >"$stage/container-inspect.json"
inventory="$(dirname "$0")/nacos-recovery-inventory.py"
python3 "$inventory" --container "$container" --credential-container wao-master-wao-server-1 >"$stage/inventory.json"
mkdir "$stage/publish"
# Independent watchdog survives a dropped SSH connection or a killed backup process.
systemd-run --quiet --unit="$guard" --on-active=120s /usr/bin/docker start "$container"
restart_needed=true
docker stop --time 45 "$container" >/dev/null
code="$(docker inspect -f '{{.State.ExitCode}}' "$container")"
[[ "$code" == 0 || "$code" == 143 ]] || { echo "Nacos did not stop cleanly (exit $code); refusing snapshot." >&2; exit 1; }
# An external watchdog restarting the container invalidates the snapshot boundary.
[[ "$(docker inspect -f '{{.State.Running}}' "$container")" == false ]]
timeout 45s docker commit --pause=false "$container" "$tag" >"$stage/commit.log"
docker image inspect -f '{{.Id}}' "$tag" >"$stage/image-id"
[[ "$(docker inspect -f '{{.State.Running}}' "$container")" == false ]]
docker start "$container" >/dev/null
restart_needed=false
systemctl stop "$guard.timer"
ready=false
for attempt in $(seq 1 60); do
  if docker exec "$container" curl -fsS --max-time 2 http://127.0.0.1:8848/nacos/v1/console/health/readiness >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]] || { echo 'Nacos restarted but readiness did not recover.' >&2; exit 1; }
python3 "$inventory" --container "$container" --credential-container wao-master-wao-server-1 >"$stage/inventory-after.json"
cmp "$stage/inventory.json" "$stage/inventory-after.json" || { echo 'Nacos inventory changed across snapshot; refusing publication.' >&2; exit 1; }
flock -u 8
printf 'format=1\ncreated_utc=%s\nimage=%s\nconsistency=clean-stopped-container\n' "$stamp" "$tag" >"$stage/metadata.txt"
docker save "$tag" | gzip -1 >"$stage/image.tar.gz"
gzip -t "$stage/image.tar.gz"
tar -C "$stage" -cf - image.tar.gz image-id metadata.txt container-inspect.json inventory.json |
  gpg --batch --yes --pinentry-mode loopback --passphrase-file "$PASSPHRASE_FILE" \
    --symmetric --cipher-algo AES256 --compress-algo none --output "$stage/publish/recovery.tar.gpg"
(cd "$stage/publish"; sha256sum recovery.tar.gpg >SHA256SUMS)
printf '%s\n' "$stamp" >"$stage/publish/COMPLETE"
peer "set -e; umask 077; mkdir -p '$remote'; test ! -e '$remote/$stamp'; mkdir '$remote/.incoming-$stamp'"
tar -C "$stage/publish" -cf - recovery.tar.gpg SHA256SUMS COMPLETE | peer "tar -C '$remote/.incoming-$stamp' -xf -"
peer "set -e; cd '$remote/.incoming-$stamp'; sha256sum -c SHA256SUMS; mv '$remote/.incoming-$stamp' '$remote/$stamp'"
mv "$stage/publish" "$root/$stamp"
echo "Verified encrypted Nacos peer snapshot: $stamp; isolated restore still required."
if [[ "${WAO_BACKUP_APPLY_RETENTION:-yes}" == yes ]]; then
  retention="$(dirname "$0")/peer-retention.py"
  python3 "$retention" "$root" --apply
  peer "python3 - '$remote' --apply" <"$retention"
fi
