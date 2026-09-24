#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
script="$script_dir/ci-deploy.sh"
notify="$script_dir/notify-feishu.sh"

# A backend rollout publishes the browser only after both application nodes.
slave_line="$(rg -n '^[[:space:]]*if ! retry_node_deploy Linux1 ' "$script" | cut -d: -f1)"
master_line="$(rg -n '^if ! retry_node_deploy Linux2 ' "$script" | cut -d: -f1)"
cloud_line="$(rg -n '^if ! deploy_cloud >cloud.log ' "$script" | cut -d: -f1)"
test "$slave_line" -lt "$master_line"
test "$master_line" -lt "$cloud_line"
! rg -q 'WAO_PIPELINE_RESULT=ready|early_browser_released|verify_first_node_public' "$script"
! rg -q 'WAO_PIPELINE_RESULT.*ready|首台发布完成' "$notify"
