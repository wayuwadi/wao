#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
sed -n '/^wait_until_ready() {$/,/^}$/p' \
  "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/function.sh"
sed -n '/^public_ready() {$/,/^}$/p' \
  "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/public-ready.sh"
test -s "$fixture/function.sh"
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
count=0
probe() {
  count=$((count + 1))
  printf 'probe:%s\n' "$count" >>"$LOG"
  [ "$count" -ge "${READY_AFTER:-1}" ]
}
sleep() { printf 'sleep:%s\n' "$1" >>"$LOG"; }
wait_until_ready probe
SH
LOG="$fixture/good.log" FIXTURE="$fixture" bash "$fixture/run.sh"
test "$(grep -c '^probe:' "$fixture/good.log")" = 1
! grep -q '^sleep:' "$fixture/good.log"
LOG="$fixture/late.log" FIXTURE="$fixture" READY_AFTER=4 bash "$fixture/run.sh"
test "$(grep -c '^probe:' "$fixture/late.log")" = 4
test "$(grep -c '^sleep:2$' "$fixture/late.log")" = 3
if LOG="$fixture/bad.log" FIXTURE="$fixture" READY_AFTER=99 bash "$fixture/run.sh"; then
  echo 'A never-ready entry passed the release gate.' >&2
  exit 1
fi
test "$(grep -c '^probe:' "$fixture/bad.log")" = 10
echo 'Post-deploy readiness tests passed.'
