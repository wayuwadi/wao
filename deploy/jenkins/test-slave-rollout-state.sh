#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT

# Exercise the real retry pipeline, caller and cleanup together. No SSH, Docker
# or production state is used; only the node installer and probes are stubbed.
sed -n '/^retry_node_deploy() {$/,/^}$/p' "$script_dir/ci-deploy.sh" >"$fixture/functions.sh"
sed -n '/^restore_upstreams() {$/,/^}$/p' "$script_dir/ci-deploy.sh" >>"$fixture/functions.sh"
awk '
  /^if \[ "\$slave_available" = true \]; then$/ { previous=$0; next }
  /^  if ! retry_node_deploy Linux1 / { print previous; copy=1 }
  /^if ! install_master_deploy_helper; then$/ { exit }
  copy { print }
' "$script_dir/ci-deploy.sh" >"$fixture/caller.sh"
test -s "$fixture/caller.sh"

cat >"$fixture/run.sh" <<'SH'
set -u
cd "$FIXTURE"
. ./functions.sh
slave_available=true
slave_deployed=false
master_deployed=true
master_drained=false
schema_quiesced=false
schema_rollout_complete=false
linux_only_schema_rollout=false
WAO_GIT_SHA=test-release
SLAVE_HOST=test-slave
ALLOW_SLAVE_OFFLINE="${ALLOW_OFFLINE:-false}"
deploy_slave() { [ "${INSTALL_FAIL:-false}" != true ]; }
gateway_ready_from_cloud() { [ "${PROBE_FAIL:-false}" != true ]; }
set_status() { :; }
set_upstream_state() { printf '%s %s\n' "$1" "$2" >>"$STATE_LOG"; }
sleep() { :; }
trap restore_upstreams EXIT
. ./caller.sh
printf 'deployed=%s available=%s\n' "$slave_deployed" "$slave_available"
SH

run_case() {
  local name="$1" expected_exit="$2" expected_state="$3"
  shift 3
  local status=0
  env FIXTURE="$fixture" STATE_LOG="$fixture/$name.state" "$@" \
    bash "$fixture/run.sh" >"$fixture/$name.log" 2>&1 || status=$?
  if [ "$status" -ne "$expected_exit" ]; then
    cat "$fixture/$name.log"
    echo "$name: expected exit $expected_exit, got $status" >&2
    exit 1
  fi
  actual="$(grep '^slave ' "$fixture/$name.state" | tail -n 1)"
  test "$actual" = "slave $expected_state" || {
    cat "$fixture/$name.state"
    echo "$name: cleanup left $actual, expected slave $expected_state" >&2
    exit 1
  }
}

run_case success 0 up
grep -q 'deployed=true available=true' "$fixture/success.log"
run_case installer_failure 1 down INSTALL_FAIL=true
test "$(grep -c 'deployment attempt' "$fixture/installer_failure.log")" = 3
run_case authorized_offline 0 down PROBE_FAIL=true ALLOW_OFFLINE=true
grep -q 'deployed=false available=false' "$fixture/authorized_offline.log"
run_case probe_failure 1 down PROBE_FAIL=true
# A failed cloud probe must not be undone by cleanup, even after installation.
echo 'Slave rollout state regression checks passed.'
