#!/usr/bin/env bash
set -euo pipefail

root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
sed -n '/^deploy_slave() {$/,/^}$/p' "$root/deploy/jenkins/ci-deploy.sh" >"$fixture/deploy-slave.sh"

cat >"$fixture/run.sh" <<'EOF'
set -euo pipefail
source "$FIXTURE/deploy-slave.sh"
WAO_RUN_TEMP="$FIXTURE/tmp"
WAO_GIT_SHA=abc1234
BUILD_NUMBER=534
WAO_BUILD_ATTEMPT=2
BOOTSTRAP_SLAVE="${BOOTSTRAP_SLAVE:-false}"
mkdir -p "$WAO_RUN_TEMP"
prepare_slave_images() {
  : >"$WAO_RUN_TEMP/wao-image-manifest.txt"
  if [ "${WITH_IMAGE:-false}" = true ]; then
    : >"$WAO_RUN_TEMP/wao-images.tar.gz"
  fi
}
retry_remote() {
  printf '%s\n' "$*" >>"$LOG"
  if [ "${MOCK_FAIL_TRANSFER:-false}" = true ] && [ "$2" = slave_scp ]; then return 7; fi
}
run_slave_installer() { printf 'installer %s\n' "$1" >>"$LOG"; }
deploy_slave
EOF

run_case() {
  local name="$1"
  shift
  : >"$fixture/$name.log"
  (cd "$fixture" && FIXTURE="$fixture" LOG="$fixture/$name.log" "$@" bash "$fixture/run.sh")
}

run_case base env
[ "$(grep -c 'slave_scp' "$fixture/base.log")" -eq 1 ]
grep -Fq 'wao-release.tgz wao-secrets.env wao-shared.env wao-gateway.p12' "$fixture/base.log"
grep -Fq 'wao-image-manifest.txt deploy/scripts/install-release-linux.sh' "$fixture/base.log"
grep -Fq "installer sh 'deploy/incoming/534-2/install-release-linux.sh' 'deploy/incoming/534-2/wao-release.tgz'" "$fixture/base.log"
! grep -q ' mv ' "$fixture/base.log"

: >"$fixture/wao-bootstrap.sql.gz"
printf 'snapshot\n' >"$fixture/wao-bootstrap.sql.gz"
run_case optional env WITH_IMAGE=true BOOTSTRAP_SLAVE=true
[ "$(grep -c 'slave_scp' "$fixture/optional.log")" -eq 3 ]
grep -Fq "'deploy/incoming/534-2/wao-images.tar.gz'" "$fixture/optional.log"
grep -Fq "'deploy/incoming/534-2/wao-bootstrap.sql.gz'" "$fixture/optional.log"

if run_case failed env MOCK_FAIL_TRANSFER=true; then
  echo 'Failed transfer incorrectly invoked the installer' >&2
  exit 1
fi
! grep -q '^installer ' "$fixture/failed.log"
echo 'Linux1 单批传输、按需文件与失败阻断检查通过。'
