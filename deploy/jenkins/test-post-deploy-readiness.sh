#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
sed -n '/^verify_post_deploy_stability() {$/,/^}$/p' \
  "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/function.sh"
sed -n '/^public_ready() {$/,/^}$/p' \
  "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/public-ready.sh"
cat >"$fixture/probe.sh" <<'SH'
set -eu
source "$FIXTURE/public-ready.sh"
curl() { printf '%s' "$PROBE_RESPONSE"; }
public_ready
SH
FIXTURE="$fixture" PROBE_RESPONSE='{"code":0,"data":{"protocol":1}}' bash "$fixture/probe.sh"
for body in '{"code":0}' '{"code":503,"data":{"protocol":1}}'; do
  if FIXTURE="$fixture" PROBE_RESPONSE="$body" bash "$fixture/probe.sh"; then
    echo "Non-ready response passed the public release gate: $body" >&2
    exit 1
  fi
done
cat >"$fixture/run.sh" <<'SH'
set -eu
source "$FIXTURE/function.sh"
MASTER_HOST=master
SLAVE_HOST=slave
slave_available="${SLAVE_AVAILABLE:-true}"
count=0
gateway_ready_from_cloud() {
  printf 'node:%s\n' "$1" >>"$LOG"
  if [ "$1" = master ] && [ "$count" -ge "${NODE_FAIL_AFTER:-100}" ]; then return 1; fi
  [ "$count" -lt "${FAIL_AFTER:-100}" ]
}
public_ready() {
  count=$((count + 1))
  printf 'public:%s\n' "$count" >>"$LOG"
  [ "$count" -lt "${FAIL_AFTER:-100}" ]
}
sleep() { printf 'sleep:%s\n' "$1" >>"$LOG"; }
verify_post_deploy_stability
SH
LOG="$fixture/good.log" FIXTURE="$fixture" bash "$fixture/run.sh"
test "$(grep -c '^node:master$' "$fixture/good.log")" = 9
test "$(grep -c '^node:slave$' "$fixture/good.log")" = 9
test "$(grep -c '^public:' "$fixture/good.log")" = 9
test "$(grep -c '^sleep:15$' "$fixture/good.log")" = 8
if LOG="$fixture/bad.log" FIXTURE="$fixture" FAIL_AFTER=5 bash "$fixture/run.sh"; then
  echo 'Delayed database readiness failure passed the release gate.' >&2
  exit 1
fi
test "$(grep -c '^public:' "$fixture/bad.log")" = 5
if LOG="$fixture/node-bad.log" FIXTURE="$fixture" NODE_FAIL_AFTER=4 bash "$fixture/run.sh"; then
  echo 'Delayed node readiness failure passed the release gate.' >&2
  exit 1
fi
test "$(grep -c '^public:' "$fixture/node-bad.log")" = 4
LOG="$fixture/slave-offline.log" FIXTURE="$fixture" SLAVE_AVAILABLE=false bash "$fixture/run.sh"
! grep -q '^node:slave$' "$fixture/slave-offline.log"
echo 'Post-deploy readiness stability tests passed.'
