#!/bin/sh
# Run inside the pinned existing redis:7-alpine image with --network none,
# --tmpfs /data and only this directory mounted read-only at /opt/wao.
set -eu
cd /data
redis_pid=''
sentinel_pid=''
cleanup() { kill ${redis_pid:-} ${sentinel_pid:-} 2>/dev/null || true; }
trap cleanup EXIT
if sh /opt/wao/start-redis.sh 2>/dev/null; then exit 1; else test "$?" = 2; fi
if sh /opt/wao/start-sentinel.sh 2>/dev/null; then exit 1; else test "$?" = 2; fi
mkdir -p /data/wao-runtime
cat >/data/wao-runtime/redis.conf <<'EOF'
bind 127.0.0.1
port 6379
dir /data
appendonly yes
appendfsync always
save ""
EOF
wait_ready() {
  remaining=50
  until redis-cli -p "$1" ping 2>/dev/null | grep -q '^PONG$'; do
    remaining=$((remaining-1)); test "$remaining" -gt 0
    sleep 0.1
  done
}
start_redis() {
  sh /opt/wao/start-redis.sh >/data/redis-test.log 2>&1 & redis_pid=$!
  wait_ready 6379
}
restart_redis() {
  redis-cli shutdown
  wait "$redis_pid"
  start_redis
}
start_redis
test "$(redis-cli set durable-probe preserved)" = OK
test "$(redis-cli replicaof 127.0.0.1 6399)" = OK
test "$(redis-cli config rewrite)" = OK
restart_redis
test "$(redis-cli --raw role | head -n1)" = slave
test "$(redis-cli replicaof no one)" = OK
test "$(redis-cli config rewrite)" = OK
restart_redis
test "$(redis-cli --raw role | head -n1)" = master
test "$(redis-cli get durable-probe)" = preserved
cat >/data/sentinel.conf <<'EOF'
bind 127.0.0.1
port 26379
dir /data
sentinel monitor wao-master 127.0.0.1 6379 3
EOF
sh /opt/wao/start-sentinel.sh >/data/sentinel-test.log 2>&1 & sentinel_pid=$!
wait_ready 26379
identity="$(redis-cli -p 26379 sentinel myid)"
test "$(redis-cli -p 26379 sentinel remove wao-master)" = OK
test "$(redis-cli -p 26379 sentinel monitor wao-master 127.0.0.2 6380 3)" = OK
test "$(redis-cli -p 26379 sentinel flushconfig)" = OK
redis-cli -p 26379 shutdown
wait "$sentinel_pid"
sh /opt/wao/start-sentinel.sh >/data/sentinel-test.log 2>&1 & sentinel_pid=$!
wait_ready 26379
test "$(redis-cli -p 26379 sentinel myid)" = "$identity"
test "$(redis-cli -p 26379 --raw sentinel get-master-addr-by-name wao-master | tr '\n' ':')" = '127.0.0.2:6380:'
echo 'PASS: missing state fails closed; demotion, promotion, data, Sentinel identity and changed target survive restart'
