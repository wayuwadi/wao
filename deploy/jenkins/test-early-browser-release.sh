#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
script="$script_dir/ci-deploy.sh"
notify="$script_dir/notify-feishu.sh"

# Immutable staging may overlap rolling deployment; the browser entry switches
# only after both nodes have passed cloud readiness and staging has completed.
line_of() { rg -n "$1" "$script" | tail -n 1 | cut -d: -f1; }
stage_job="$(line_of '^run_stage_job cloud-stage.log ')"
rollout="$(line_of '^if \[ "\$master_routable" = true \]; then')"
waited="$(line_of '^wait "\$stage_pid"')"
slave_ready="$(line_of '^if \[ "\$slave_available" = true \] && ! wait_until_ready gateway_ready_from_cloud')"
activate="$(line_of '^if ! activate_cloud >cloud.log ')"
test "$stage_job" -lt "$rollout"
test "$rollout" -lt "$waited"
test "$waited" -lt "$slave_ready"
test "$slave_ready" -lt "$activate"
! rg -q '^run_node_job Linux[12].*&$' "$script"
# Staging must not flip the public routing manifest.
! sed -n '/^stage_cloud() {$/,/^}$/p' "$script" | rg -q 'publish-static-routing.py /etc'
rg -q 'stage_slave_static &|stage_slave_static; result=' "$script"
rg -q 'stage_cloud_static &|stage_cloud_static; result=' "$script"
! rg -q 'scp .*wao-web/dist|/tmp/wao-dist' "$script"
rg -q 'release_dir=/var/www/wao/releases/' "$script"
! rg -q 'WAO_PIPELINE_RESULT=ready|early_browser_released|verify_first_node_public' "$script"
! rg -q 'WAO_PIPELINE_RESULT.*ready|首台发布完成' "$notify"
