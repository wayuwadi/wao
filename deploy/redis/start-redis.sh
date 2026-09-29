#!/bin/sh
set -eu

# Redis starts only from the durable local configuration; releases never infer its role.
state=/data/wao-runtime/redis.conf
if [ ! -s "$state" ] || [ -L "$state" ]; then
  echo 'Redis durable state is absent; prepare or restore it before starting. No role was assumed.' >&2
  exit 2
fi
chmod 700 /data/wao-runtime
chmod 600 "$state"
chown redis:redis /data/wao-runtime "$state"
exec /usr/local/bin/docker-entrypoint.sh redis-server "$state"
