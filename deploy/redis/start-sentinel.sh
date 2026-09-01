#!/bin/sh
set -eu

: "${REDIS_MASTER_HOST:?REDIS_MASTER_HOST is required}"
: "${REDIS_PASS:?REDIS_PASS is required}"
: "${SENTINEL_ANNOUNCE_HOST:?SENTINEL_ANNOUNCE_HOST is required}"

sentinel_quorum="${REDIS_SENTINEL_QUORUM:-3}"
case "${sentinel_quorum}" in
  ''|*[!0-9]*|0) echo 'REDIS_SENTINEL_QUORUM must be a positive integer.' >&2; exit 2 ;;
esac

cat >/tmp/sentinel.conf <<EOF
port 26379
bind 0.0.0.0
protected-mode yes
requirepass ${REDIS_PASS}
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel announce-ip ${SENTINEL_ANNOUNCE_HOST}
sentinel announce-port 26379
sentinel monitor ${REDIS_MASTER_NAME:-wao-master} ${REDIS_MASTER_HOST} 6379 ${sentinel_quorum}
sentinel auth-pass ${REDIS_MASTER_NAME:-wao-master} ${REDIS_PASS}
sentinel down-after-milliseconds ${REDIS_MASTER_NAME:-wao-master} 5000
sentinel failover-timeout ${REDIS_MASTER_NAME:-wao-master} 60000
sentinel parallel-syncs ${REDIS_MASTER_NAME:-wao-master} 1
EOF

exec redis-sentinel /tmp/sentinel.conf
