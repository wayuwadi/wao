#!/bin/sh
set -eu

# Keep myid, epochs, discovered peers and the elected master across recreation.
state=/data/sentinel.conf
if [ ! -s "$state" ] || [ -L "$state" ]; then
  echo 'Sentinel durable state is absent; prepare or restore it before starting. No topology was reset.' >&2
  exit 2
fi
chmod 700 /data
chmod 600 "$state"
chown redis:redis /data "$state"
exec /usr/local/bin/docker-entrypoint.sh redis-server "$state" --sentinel
