#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
sed -n '/^deploy_browser_only() {$/,/^}$/p' "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/function.sh"
cat >"$fixture/run.sh" <<'SH'
set -eu
source "$FIXTURE/function.sh"
WAO_CHANGE_BASE=base
WAO_GIT_SHA=target
build_web="${BUILD_WEB:-true}"
availability_failures="$FIXTURE/failures"
: >"$availability_failures"
sh() { printf 'WAO_WEB_ONLY=%s\n' "${PLAN:-true}"; }
prepare_cloud_routing() { echo routing >>"$LOG"; [ "${ROUTING_FAIL:-false}" != true ]; }
wait_until_ready() { "$@"; }
gateway_ready_from_cloud() { echo gateway_probe >>"$LOG"; [ "${PROBE_FAIL:-false}" != true ]; }
start_availability_monitor() { echo monitor_start >>"$LOG"; }
stage_cloud() { echo stage >>"$LOG"; [ "${STAGE_FAIL:-false}" != true ]; }
publish_release() { echo publish >>"$LOG"; [ "${PUBLISH_FAIL:-false}" != true ]; }
set_status() { echo "$1:$2" >>"$LOG"; }
deploy_browser_only
SH
run_case() {
  local name="$1" expected="$2" actual=0
  shift 2
  : >"$fixture/$name.log"
  (
    export FIXTURE="$fixture" LOG="$fixture/$name.log"
    for variable in "$@"; do export "$variable"; done
    bash "$fixture/run.sh"
  ) || actual=$?
  if [[ "$expected" = success ]]; then test "$actual" = 0; else test "$actual" != 0; fi
}
run_case good success
grep -qx routing "$fixture/good.log"
grep -qx gateway_probe "$fixture/good.log"
grep -qx stage "$fixture/good.log"
grep -qx publish "$fixture/good.log"
grep -qx 'linux2_status:success' "$fixture/good.log"
run_case game_only success BUILD_WEB=false
! grep -q routing "$fixture/game_only.log"
grep -qx stage "$fixture/game_only.log"
grep -qx publish "$fixture/game_only.log"
run_case mixed failure PLAN=false
! grep -q publish "$fixture/mixed.log"
run_case routing_failure failure ROUTING_FAIL=true
! grep -q stage "$fixture/routing_failure.log"
run_case unhealthy failure PROBE_FAIL=true
! grep -q stage "$fixture/unhealthy.log"
run_case stage_failure failure STAGE_FAIL=true
! grep -q 'linux2_status:success' "$fixture/stage_failure.log"
run_case publish_failure failure PUBLISH_FAIL=true
! grep -q 'linux2_status:success' "$fixture/publish_failure.log"
echo 'Linux2-only browser release guard checks passed.'
