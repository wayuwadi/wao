#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT

for name in wait_until_stably_ready start_availability_monitor stop_availability_monitor \
  finish_single_node_restart_window publish_release; do
  sed -n "/^${name}() {$/,/^}$/p" "$root/deploy/jenkins/ci-deploy.sh" >>"$fixture/functions.sh"
done

cat >"$fixture/run.sh" <<'SH'
set -euo pipefail
source "$FIXTURE/functions.sh"
cd "$FIXTURE"
WAO_RUN_TEMP="$FIXTURE"
WAO_GIT_SHA=1234567
availability_pid=''
availability_stop="$FIXTURE/stop"
availability_failures="$FIXTURE/failures"
: >"$availability_failures"

public_ready() { test -f "$FIXTURE/healthy"; }
sleep() { command sleep 0.03; }
activate_cloud() { :; }
set_status() { printf '%s=%s\n' "$1" "$2" >>"$FIXTURE/status"; }
wait_for_sample() {
  local attempt
  for attempt in {1..50}; do
    [ -s "$availability_failures" ] && return 0
    sleep 0.03
  done
  return 1
}

# The only backend restarts while public traffic is briefly unavailable.
start_availability_monitor
wait_for_sample
touch "$FIXTURE/healthy"
wait_until_stably_ready public_ready
finish_single_node_restart_window >"$FIXTURE/restart.log"
grep -Eq '不可用采样 [1-9][0-9]* 次' "$FIXTURE/restart.log"
test ! -s "$availability_failures"
publish_release
grep -q '^verification_status=success$' "$FIXTURE/status"

# A backend that never recovers cannot be accepted as planned downtime.
rm -f "$FIXTURE/healthy"
start_availability_monitor
if publish_release; then
  echo 'Unrecovered public entry passed the release gate.' >&2
  exit 1
fi
grep -q '^verification_status=failed$' "$FIXTURE/status"
stop_availability_monitor
: >"$availability_failures"

# Once public traffic has recovered, a new failure must still fail release.
start_availability_monitor
wait_for_sample
touch "$FIXTURE/healthy"
if publish_release; then
  echo 'Post-restart public outage passed the release gate.' >&2
  exit 1
fi
grep -q '^verification_status=failed$' "$FIXTURE/status"

# A flapping endpoint is not stably ready.
probe_count=0
public_ready() {
  probe_count=$((probe_count + 1))
  [ "$probe_count" -ge 3 ] && [ "$probe_count" -le 4 ] || [ "$probe_count" -ge 7 ]
}
wait_until_stably_ready public_ready
test "$probe_count" = 9
probe_count=0
public_ready() { probe_count=$((probe_count + 1)); [ "$((probe_count % 2))" = 1 ]; }
if wait_until_stably_ready public_ready; then
  echo 'Flapping public readiness passed the stable gate.' >&2
  exit 1
fi
test "$probe_count" = 12
SH

FIXTURE="$fixture" bash "$fixture/run.sh"
echo 'Single-node restart availability tests passed.'
