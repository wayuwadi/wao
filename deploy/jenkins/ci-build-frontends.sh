#!/bin/sh
# Build both browser bundles concurrently; report either failure after both exit.
set -eu
: "${WAO_RUN_TEMP:?}"
mkdir -p "$WAO_RUN_TEMP"

deploy/jenkins/ci-frontend-build.sh wao-web false true >"$WAO_RUN_TEMP/wao-web-build.log" 2>&1 &
web_pid=$!
deploy/jenkins/ci-frontend-build.sh agent-web false true >"$WAO_RUN_TEMP/agent-web-build.log" 2>&1 &
agent_pid=$!

set +e
wait "$web_pid"
web_result=$?
wait "$agent_pid"
agent_result=$?
set -e

echo '===== WAO 网页构建 ====='
cat "$WAO_RUN_TEMP/wao-web-build.log"
echo '===== Agent 网页构建 ====='
cat "$WAO_RUN_TEMP/agent-web-build.log"
if [ "$web_result" -ne 0 ] || [ "$agent_result" -ne 0 ]; then
  printf '网页构建失败：WAO=%s，Agent=%s\n' "$web_result" "$agent_result" >&2
  exit 1
fi
echo '两个网页构建均已完成。'
