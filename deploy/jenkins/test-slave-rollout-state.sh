#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT

for function in restore_upstreams rollout_slave rollout_master; do
  sed -n "/^${function}() {\$/,/^}\$/p" "$script_dir/ci-deploy.sh" >>"$fixture/functions.sh"
done
test "$(grep -c '() {$' "$fixture/functions.sh")" = 3

cat >"$fixture/run.sh" <<'SH'
set -u
. "$FIXTURE/functions.sh"
WAO_RUN_TEMP="$FIXTURE"
SLAVE_HOST=linux1
MASTER_HOST=linux2
recorded_result=pending
master_result=pending
slave_result=pending
master_routable="${MASTER_READY:-true}"
slave_routable="${SLAVE_READY:-true}"
retry_remote() { shift; "$@"; }
ssh() {
  case "${@: -1}" in
    *'master:up slave:up'*) echo 'master:up slave:up' >>"$FIXTURE/routes" ;;
    *'master:up slave:down'*) echo 'master:up slave:down' >>"$FIXTURE/routes" ;;
    *'master:down slave:up'*) echo 'master:down slave:up' >>"$FIXTURE/routes" ;;
    *) return 1 ;;
  esac
}
set_status() { printf '%s=%s\n' "$1" "$2" >>"$FIXTURE/status"; }
wait_until_ready() { "$@"; }
public_ready() { return 0; }
gateway_ready_from_cloud() {
  case "$1" in
    linux1) [ "${SLAVE_GATEWAY_FAIL:-false}" != true ] ;;
    linux2) [ "${MASTER_GATEWAY_FAIL:-false}" != true ] ;;
  esac
}
run_node_job() {
  local result=success
  if [ "$1" = Linux1 ] && [ "${SLAVE_DEPLOY_FAIL:-false}" = true ]; then result=failed; fi
  if [ "$1" = Linux2 ] && [ "${MASTER_DEPLOY_FAIL:-false}" = true ]; then result=failed; fi
  printf '%s 1 0\n' "$result" >"$4"
  printf '%s\n' "$1" >>"$FIXTURE/jobs"
}
record_node_result() { read -r recorded_result _ <"$3"; }
case "$CASE" in
  healthy) rollout_slave && rollout_master ;;
  slave_failure) if rollout_slave; then exit 1; fi ;;
  master_failure) rollout_slave && { if rollout_master; then exit 1; fi; } ;;
  master_initially_down) rollout_master && rollout_slave ;;
  only_master_healthy) if rollout_master; then exit 1; fi ;;
esac
printf 'master=%s slave=%s\n' "$master_routable" "$slave_routable"
SH

run_case() {
  local name="$1" expected="$2" jobs="$3" routes="$4"
  shift 4
  : >"$fixture/routes"
  : >"$fixture/jobs"
  : >"$fixture/status"
  env FIXTURE="$fixture" CASE="$name" "$@" bash "$fixture/run.sh" >"$fixture/output"
  grep -qx "$expected" "$fixture/output"
  test "$(paste -sd, "$fixture/jobs")" = "$jobs"
  test "$(paste -sd, "$fixture/routes")" = "$routes"
}

run_case healthy 'master=true slave=true' 'Linux1,Linux2' \
  'master:up slave:down,master:up slave:up,master:down slave:up,master:up slave:up'
run_case slave_failure 'master=true slave=false' 'Linux1' \
  'master:up slave:down' SLAVE_DEPLOY_FAIL=true
run_case master_failure 'master=false slave=true' 'Linux1,Linux2' \
  'master:up slave:down,master:up slave:up,master:down slave:up' MASTER_DEPLOY_FAIL=true
run_case master_initially_down 'master=true slave=true' 'Linux2,Linux1' \
  'master:down slave:up,master:up slave:up,master:up slave:down,master:up slave:up' MASTER_READY=false
run_case only_master_healthy 'master=true slave=false' '' '' SLAVE_READY=false
echo 'Rolling deployment availability checks passed.'
