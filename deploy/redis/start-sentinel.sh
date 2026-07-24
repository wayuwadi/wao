#!/bin/sh
set -eu

: "${REDIS_MASTER_HOST:?REDIS_MASTER_HOST is required}"
: "${REDIS_PASS:?REDIS_PASS is required}"
: "${SENTINEL_ANNOUNCE_HOST:?SENTINEL_ANNOUNCE_HOST is required}"

cat >/tmp/sentinel.conf <<EOF
port 26379
bind 0.0.0.0
protected-mode yes
requirepass ${REDIS_PASS}
resolve-hostnames yes
announce-hostnames yes
sentinel announce-ip ${SENTINEL_ANNOUNCE_HOST}
sentinel announce-port 26379
sentinel monitor ${REDIS_MASTER_NAME:-wao-master} ${REDIS_MASTER_HOST} 6379 2
sentinel auth-pass ${REDIS_MASTER_NAME:-wao-master} ${REDIS_PASS}
sentinel down-after-milliseconds ${REDIS_MASTER_NAME:-wao-master} 5000
sentinel failover-timeout ${REDIS_MASTER_NAME:-wao-master} 60000
sentinel parallel-syncs ${REDIS_MASTER_NAME:-wao-master} 1
EOF

exec redis-sentinel /tmp/sentinel.conf
