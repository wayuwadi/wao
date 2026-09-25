#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT

# Exercise the real parallel job wrapper, result recording and upstream restore.
# No SSH, Docker or production state is used; only installers and SSH are stubbed.
for function in retry_node_deploy run_node_job record_node_result restore_upstreams; do
  sed -n "/^${function}() {\$/,/^}\$/p" "$script_dir/ci-deploy.sh" >>"$fixture/functions.sh"
done
test "$(grep -c '() {$' "$fixture/functions.sh")" = 4

cat >"$fixture/run.sh" <<'SH'
set -u
cd "$FIXTURE"
. ./functions.sh
WAO_RUN_TEMP="$FIXTURE"
WAO_GIT_SHA=test-release
slave_available="${SLAVE_AVAILABLE:-true}"
status_file="$FIXTURE/status.env"
: >"$status_file"
set_status() { printf '%s=%s\n' "$1" "$2" >>"$status_file"; }
retry_remote() { shift; "$@"; }
ssh() { printf '%s\n' "${@: -1}" >"$FIXTURE/remote.sh"; }
sleep() { :; }
deploy_master() { echo master-installed; [ "${MASTER_FAIL:-false}" != true ]; }
deploy_slave() { echo slave-installed; [ "${SLAVE_FAIL:-false}" != true ]; }
run_node_job Linux2 deploy_master master.log "$FIXTURE/master.result" &
run_node_job Linux1 deploy_slave slave.log "$FIXTURE/slave.result" &
wait
record_node_result master master.log "$FIXTURE/master.result" >/dev/null
master_result="$recorded_result"
record_node_result slave slave.log "$FIXTURE/slave.result" >/dev/null
slave_result="$recorded_result"
restore_upstreams
printf 'master=%s slave=%s\n' "$master_result" "$slave_result"
SH

run_case() {
  local name="$1" expected="$2" master_state="$3" slave_state="$4"
  shift 4
  rm -f "$fixture"/*.result "$fixture/remote.sh"
  env FIXTURE="$fixture" "$@" bash "$fixture/run.sh" >"$fixture/$name.log" 2>&1
  grep -qx "$expected" "$fixture/$name.log" || { cat "$fixture/$name.log"; exit 1; }
  grep -q "master:$master_state slave:$slave_state" "$fixture/remote.sh" || {
    cat "$fixture/remote.sh"
    echo "$name: expected master:$master_state slave:$slave_state" >&2
    exit 1
  }
}

run_case success 'master=success slave=success' up up
grep -qx 'slave_status=success' "$fixture/status.env"
grep -qx 'master_attempts=1' "$fixture/status.env"
run_case slave_failure 'master=success slave=failed' up down SLAVE_FAIL=true
grep -qx 'slave_attempts=3' "$fixture/status.env"
grep -qx 'slave_status=failed' "$fixture/status.env"
run_case master_failure 'master=failed slave=success' down up MASTER_FAIL=true
run_case authorized_offline 'master=success slave=success' up down SLAVE_AVAILABLE=false
# Healthy nodes without a stale drain marker must not re-render Nginx.
sh -n "$fixture/remote.sh"
grep -q 'continue' "$fixture/remote.sh"
echo 'Parallel node rollout state regression checks passed.'
