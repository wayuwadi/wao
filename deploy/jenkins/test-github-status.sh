#!/bin/sh
set -eu

project_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
test_root="$(mktemp -d)"
bin_dir="$test_root/bin"
run_dir="$test_root/run"
capture="$test_root/payload.json"
mkdir -p "$bin_dir" "$run_dir"

printf '%s\n' \
  'linux2_status=failed' \
  'linux2_detail=JumpServer Linux2/云入口验收失败：真实会话未建立' \
  >"$run_dir/wao-deploy-status.env"

printf '%s\n' \
  '#!/bin/sh' \
  'set -eu' \
  'payload=""' \
  'while [ "$#" -gt 0 ]; do' \
  '  if [ "$1" = --data ]; then shift; payload="$1"; fi' \
  '  shift' \
  'done' \
  'printf "%s" "$payload" >"$GITHUB_STATUS_CAPTURE"' \
  >"$bin_dir/curl"
chmod +x "$bin_dir/curl"

PATH="$bin_dir:$PATH" \
GITHUB_STATUS_CAPTURE="$capture" \
GITHUB_PAT=test-token \
WAO_GIT_SHA=0123456789abcdef0123456789abcdef01234567 \
WAO_RUN_TEMP="$run_dir" \
BUILD_NUMBER=999 \
  sh "$project_root/deploy/jenkins/github-status.sh" failure 'Jenkins 失败：Linux2 单节点发布'

jq -e '
  .state == "failure"
  and .context == "jenkins/wao-main-cd"
  and .description == "Jenkins 失败：Linux2 单节点发布：JumpServer Linux2/云入口验收失败：真实会话未建立"
  and (.description | length) <= 140
' "$capture" >/dev/null

echo 'Jenkins GitHub failure detail tests passed.'
