#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
sed -n '/^deploy_browser_only() {$/,/^}$/p' "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/function.sh"
cat >"$fixture/run.sh" <<'SH'
set -eu
source "$FIXTURE/function.sh"
BOOTSTRAP_SLAVE="${BOOTSTRAP:-false}"
ALLOW_SLAVE_OFFLINE=false
WAO_CHANGE_BASE=base
WAO_GIT_SHA=target
MASTER_HOST=master
SLAVE_HOST=slave
availability_failures="$FIXTURE/failures"
: >"$availability_failures"
sh() { printf 'WAO_WEB_ONLY=%s\n' "${PLAN:-true}"; }
prepare_cloud_routing() { echo routing >>"$LOG"; }
gateway_ready_from_cloud() { echo "probe:$1" >>"$LOG"; [ "${PROBE_FAIL:-false}" != true ]; }
start_availability_monitor() { echo start >>"$LOG"; }
stop_availability_monitor() { echo stop >>"$LOG"; }
deploy_cloud() { echo publish >>"$LOG"; [ "${PUBLISH_FAIL:-false}" != true ]; }
set_status() { echo "$1:$2" >>"$LOG"; }
deploy_browser_only
SH
run_case() {
  local name="$1" expected="$2" actual=0
  shift 2
  : >"$fixture/$name.log"
  env FIXTURE="$fixture" LOG="$fixture/$name.log" "$@" bash "$fixture/run.sh" || actual=$?
  if [[ "$expected" = success ]]; then test "$actual" = 0; else test "$actual" != 0; fi
}
run_case good success
test "$(grep -c '^probe:' "$fixture/good.log")" = 4
grep -qx 'publish' "$fixture/good.log"
test "$(grep -nE '^(routing|publish)$' "$fixture/good.log" | cut -d: -f2)" = "$(printf 'routing\npublish')"
grep -qx 'cloud_status:success' "$fixture/good.log"
run_case mixed failure PLAN=false
! grep -q publish "$fixture/mixed.log"
run_case bootstrap failure BOOTSTRAP=true
! grep -q publish "$fixture/bootstrap.log"
run_case unhealthy failure PROBE_FAIL=true
! grep -q publish "$fixture/unhealthy.log"
run_case upload_failure failure PUBLISH_FAIL=true
! grep -q 'cloud_status:success' "$fixture/upload_failure.log"
echo 'Browser release guard and failure tests passed.'
