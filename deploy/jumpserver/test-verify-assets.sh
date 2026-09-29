#!/bin/sh
set -eu
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/data/core/data/bootstrap"
touch "$test_root/data/core/data/bootstrap/wao-assets" "$test_root/asset-known-hosts"
cat >"$test_root/assets.env" <<'ENV'
WAO_JMS_MASTER_ADDRESS=linux2.example
WAO_JMS_MASTER_USER=master
WAO_JMS_CLOUD_ADDRESS=cloud.example
WAO_JMS_CLOUD_USER=cloud
ENV
cat >"$test_root/bin/ssh" <<'SH'
#!/bin/sh
printf '%s\n' "$*" >>"$TEST_LOG"
case "$*" in *cloud@cloud.example*) [ "${FAIL_CLOUD:-false}" = false ] || exit 92 ;; esac
SH
cat >"$test_root/bin/sudo" <<'SH'
#!/bin/sh
shift
if [ "$1" = install ]; then exec "$@"; fi
printf '%s\n' "$*" >>"$TEST_LOG"
case "$*" in *verify_wao_ssh.py*) [ "${FAIL_CORE:-false}" = false ] || exit 93 ;; esac
SH
chmod +x "$test_root/bin/ssh" "$test_root/bin/sudo"
export PATH="$test_root/bin:$PATH" TEST_LOG="$test_root/calls" JUMPSERVER_STATE_ROOT="$test_root"
sh deploy/jumpserver/verify-assets.sh >"$test_root/output"
grep -q 'Linux2/cloud inventory' "$test_root/output"
test "$(grep -c 'manage.py shell' "$TEST_LOG")" -eq 3
grep -q 'verify_wao_ssh.py' "$TEST_LOG"
export FAIL_CLOUD=true
: >"$TEST_LOG"
if sh deploy/jumpserver/verify-assets.sh >"$test_root/output"; then exit 1; fi
! grep -q 'docker exec' "$TEST_LOG"
! grep -q 'Linux2/cloud inventory' "$test_root/output"
export FAIL_CLOUD=false FAIL_CORE=true
: >"$TEST_LOG"
if sh deploy/jumpserver/verify-assets.sh >"$test_root/output"; then exit 1; fi
! grep -q 'verify_wao_sso.py' "$TEST_LOG"
! grep -q 'Linux2/cloud inventory' "$test_root/output"
echo 'JumpServer current-host probes and failure propagation passed.'
