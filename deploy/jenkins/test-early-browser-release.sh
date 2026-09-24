#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
awk '
  /^# 常规完整发布先让 Linux1 独立承载普通公网流量/ { copy=1 }
  /^if \[ "\$safe_schema_rollout" != true \] && \[ "\$early_browser_released" != true \]; then$/ { exit }
  copy { print }
' "$script_dir/ci-deploy.sh" >"$fixture/early.sh"
test -s "$fixture/early.sh"
sed -n '/^verify_first_node_public() {$/,/^}$/p' \
  "$script_dir/ci-deploy.sh" >"$fixture/verify.sh"
test -s "$fixture/verify.sh"
mkdir -p "$fixture/deploy/jenkins"
cat >"$fixture/deploy/jenkins/notify-feishu.sh" <<'SH'
#!/bin/sh
printf 'notify:%s\n' "$WAO_PIPELINE_RESULT" >>"$WAO_TEST_LOG"
SH
chmod +x "$fixture/deploy/jenkins/notify-feishu.sh"
cat >"$fixture/run.sh" <<'SH'
set -u
cd "$FIXTURE"
slave_deployed="${SLAVE_DEPLOYED:-true}"
safe_schema_rollout="${SCHEMA_ROLLOUT:-false}"
BOOTSTRAP_SLAVE=false
ALLOW_SLAVE_OFFLINE=false
master_drained=false
early_browser_released=false
WAO_GIT_SHA=abcdef1234567890abcdef1234567890abcdef12
WAO_TEST_LOG="$LOG"
export WAO_TEST_LOG
set_upstream_state() { printf 'route:%s:%s\n' "$1" "$2" >>"$LOG"; }
deploy_cloud() { echo cloud >>"$LOG"; return "${CLOUD_RESULT:-0}"; }
verify_first_node_public() { echo public_check >>"$LOG"; return "${CHECK_RESULT:-0}"; }
set_status() { printf 'status:%s:%s\n' "$1" "$2" >>"$LOG"; }
. "$FIXTURE/early.sh"
printf 'ready=%s drained=%s\n' "$early_browser_released" "$master_drained" >>"$LOG"
SH

run_case() {
  local name="$1" expected_exit="$2"
  shift 2
  local status=0
  env FIXTURE="$fixture" LOG="$fixture/$name.log" "$@" \
    bash "$fixture/run.sh" >"$fixture/$name.out" 2>&1 || status=$?
  test "$status" -eq "$expected_exit" || {
    cat "$fixture/$name.out" >&2
    echo "$name returned $status, expected $expected_exit" >&2
    exit 1
  }
}

run_case ready 0
test "$(grep -E '^(route:|cloud$|public_check$|notify:)' "$fixture/ready.log")" = \
  "$(printf 'route:master:down\ncloud\npublic_check\nnotify:ready')"
grep -q '^notify:ready$' "$fixture/ready.log"
grep -q '^ready=true drained=true$' "$fixture/ready.log"

run_case bad_public_version 1 CHECK_RESULT=1
! grep -q '^notify:' "$fixture/bad_public_version.log"
grep -q '^status:verification_status:failed$' "$fixture/bad_public_version.log"

run_case migration 0 SCHEMA_ROLLOUT=true
test "$(cat "$fixture/migration.log")" = 'ready=false drained=false'

run_case slave_missing 0 SLAVE_DEPLOYED=false
test "$(cat "$fixture/slave_missing.log")" = 'ready=false drained=false'

cat >"$fixture/verify-run.sh" <<'SH'
set -u
. "$FIXTURE/verify.sh"
WAO_GIT_SHA=abcdef1234567890abcdef1234567890abcdef12
SLAVE_HOST=linux1.wayuwa.cn
ssh() { [ "${MARKER_MISSING:-false}" != true ]; }
gateway_ready_from_cloud() { [ "$1" = linux1.wayuwa.cn ]; }
curl() {
  case "$*" in
    *web_version_by_plugin.json*) printf '{"version":"%s"}' "${WEB_VERSION:-abcdef1}" ;;
    *index.html*) printf '<html data-v="abcdef1"></html>' ;;
    */api/hello*) printf '{"code":0}' ;;
    *) return 1 ;;
  esac
}
verify_first_node_public
SH
FIXTURE="$fixture" bash "$fixture/verify-run.sh"
if FIXTURE="$fixture" WEB_VERSION=stale bash "$fixture/verify-run.sh"; then
  echo 'Stale public frontend must prevent first-node notification.' >&2
  exit 1
fi
if FIXTURE="$fixture" MARKER_MISSING=true bash "$fixture/verify-run.sh"; then
  echo 'Unpinned master routing must prevent first-node notification.' >&2
  exit 1
fi

echo 'First-node browser release ordering checks passed.'
