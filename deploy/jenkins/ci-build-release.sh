#!/bin/sh
# Frontend bundles and backend images use disjoint inputs and outputs. Build
# them together, but wait for both and preserve either failure as a hard gate.
set -eu
: "${WAO_RUN_TEMP:?}"
mkdir -p "$WAO_RUN_TEMP"

deploy/jenkins/ci-build-frontends.sh >"$WAO_RUN_TEMP/frontends-build.log" 2>&1 &
frontends_pid=$!
deploy/jenkins/ci-package.sh >"$WAO_RUN_TEMP/package-build.log" 2>&1 &
package_pid=$!

set +e
wait "$frontends_pid"
frontends_result=$?
wait "$package_pid"
package_result=$?
set -e

echo '===== 网页构建 ====='
cat "$WAO_RUN_TEMP/frontends-build.log"
echo '===== 镜像与源码包 ====='
cat "$WAO_RUN_TEMP/package-build.log"
if [ "$frontends_result" -ne 0 ] || [ "$package_result" -ne 0 ]; then
  printf '发布制品构建失败：网页=%s，镜像与源码包=%s\n' \
    "$frontends_result" "$package_result" >&2
  exit 1
fi
echo '网页与镜像发布制品均已完成。'
