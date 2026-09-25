#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
script="$script_dir/ci-deploy.sh"
notify="$script_dir/notify-feishu.sh"

# Both nodes deploy in parallel with immutable cloud staging; the browser entry
# switches only after both node jobs finished and passed cloud readiness.
line_of() { rg -n "$1" "$script" | head -n 1 | cut -d: -f1; }
master_job="$(line_of '^run_node_job Linux2 deploy_master ')"
slave_job="$(line_of '^  run_node_job Linux1 deploy_slave ')"
stage_job="$(line_of '^run_stage_job ')"
waited="$(line_of '^wait "\$master_pid"')"
slave_ready="$(line_of 'wait_until_ready gateway_ready_from_cloud "\$SLAVE_HOST"')"
activate="$(line_of '^if ! activate_cloud >cloud.log ')"
test "$master_job" -lt "$waited"
test "$slave_job" -lt "$waited"
test "$stage_job" -lt "$waited"
test "$waited" -lt "$slave_ready"
test "$slave_ready" -lt "$activate"
# Staging must not flip the public routing manifest.
! sed -n '/^stage_cloud() {$/,/^}$/p' "$script" | rg -q 'publish-static-routing.py /etc'
! rg -q 'WAO_PIPELINE_RESULT=ready|early_browser_released|verify_first_node_public' "$script"
! rg -q 'WAO_PIPELINE_RESULT.*ready|首台发布完成' "$notify"
