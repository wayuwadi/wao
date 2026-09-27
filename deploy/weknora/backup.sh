#!/usr/bin/env bash
# Stop only the knowledge application while taking a consistent DB/files snapshot.
set -Eeuo pipefail
umask 077
cd /opt/wao-weknora/upstream
backup_root=/var/backups/wao-weknora
mkdir -p "$backup_root"
exec 9>"$backup_root/.lock"
flock -n 9 || exit 0
stamp=$(date -u +%Y%m%dT%H%M%SZ)
destination="$backup_root/$stamp"
mkdir "$destination"
compose() { docker compose -p wao-weknora -f docker-compose.yml -f ../compose.override.yml "$@"; }
restore() { compose up -d app frontend >/dev/null; }
trap restore EXIT HUP INT TERM
compose stop frontend app >/dev/null
docker exec WeKnora-postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$destination/database.dump"
set -- .env config/config.yaml config/builtin_models.yaml ../compose.override.yml
[ ! -f weknora-sync.private.json ] || set -- "$@" weknora-sync.private.json
tar -czf "$destination/config.tgz" "$@"
for volume in data-files redis-data; do
  source=$(docker volume inspect "wao-weknora_$volume" --format '{{.Mountpoint}}')
  tar -czf "$destination/$volume.tgz" -C "$source" .
done
restore
trap - EXIT HUP INT TERM
touch "$destination/complete"
# Encrypt the completed local snapshot and publish only after remote SHA verification.
. /etc/wao-backup/cloud.env
: "${CLOUD_HOST:?}" "${CLOUD_ROOT:?}" "${SSH_KEY:?}" "${KNOWN_HOSTS:?}" "${PASSPHRASE_FILE:?}"
cloud_root="$CLOUD_ROOT/weknora"
encrypted_root=/var/backups/wao-weknora-cloud
mkdir -p "$encrypted_root"
stage=$(mktemp -d "$encrypted_root/.incoming-$stamp.XXXXXX")
cleanup() { rm -rf -- "$stage"; }
trap cleanup EXIT HUP INT TERM
tar -C "$destination" -cf - database.dump config.tgz data-files.tgz redis-data.tgz complete |
  gpg --batch --yes --pinentry-mode loopback --passphrase-file "$PASSPHRASE_FILE" \
    --symmetric --cipher-algo AES256 --compress-algo none --output "$stage/recovery.tar.gpg"
(cd "$stage" && sha256sum recovery.tar.gpg >SHA256SUMS)
printf '%s\n' "$stamp" >"$stage/COMPLETE"
ssh_cloud() {
  ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 \
    -i "$SSH_KEY" -o "UserKnownHostsFile=$KNOWN_HOSTS" "$CLOUD_HOST" "$@"
}
ssh_cloud "set -e; umask 077; mkdir -p '$cloud_root'; test ! -e '$cloud_root/$stamp'; mkdir '$cloud_root/.incoming-$stamp'"
tar -C "$stage" -cf - recovery.tar.gpg SHA256SUMS COMPLETE |
  ssh_cloud "tar -C '$cloud_root/.incoming-$stamp' -xf -"
ssh_cloud "set -e; cd '$cloud_root/.incoming-$stamp'; sha256sum -c SHA256SUMS; mv '$cloud_root/.incoming-$stamp' '$cloud_root/$stamp'"
mv "$stage" "$encrypted_root/$stamp"
trap - EXIT HUP INT TERM
printf 'WeKnora backup completed: %s\n' "$stamp"
