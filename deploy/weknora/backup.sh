#!/bin/sh
# Stop only the knowledge application while taking a consistent DB/files snapshot.
set -eu
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
tar -czf "$destination/config.tgz" .env config/config.yaml config/builtin_models.yaml ../compose.override.yml
for volume in data-files redis-data; do
  source=$(docker volume inspect "wao-weknora_$volume" --format '{{.Mountpoint}}')
  tar -czf "$destination/$volume.tgz" -C "$source" .
done
restore
trap - EXIT HUP INT TERM
touch "$destination/complete"
# The transport reuses the verified deployment identity without exporting its key.
export RSYNC_RSH='sudo -n -u jenkins ssh -i /etc/jenkins/secrets/slave-ed25519 -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/etc/jenkins/secrets/slave-known-hosts -o HostKeyAlias=linux1.wayuwa.cn -p 2201'
$RSYNC_RSH wage@127.0.0.1 'umask 077; mkdir -p backups/wao-weknora'
rsync -a --chmod=D700,F600 "$destination/" "wage@127.0.0.1:backups/wao-weknora/$stamp/"
printf 'WeKnora backup completed: %s\n' "$stamp"
