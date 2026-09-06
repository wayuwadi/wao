#!/bin/sh
set -eu
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin" "$test_root/data/core/data/bootstrap"
touch "$test_root/data/core/data/bootstrap/wao-assets" "$test_root/asset-known-hosts"
cat >"$test_root/assets.env" <<'ENV'
WAO_JMS_MASTER_ADDRESS=linux2.example
WAO_JMS_MASTER_USER=master
WAO_JMS_SLAVE_ADDRESS=127.0.0.1
WAO_JMS_SLAVE_USER=slave
WAO_JMS_SLAVE_PORT=2201
WAO_JMS_SLAVE_VIA_MASTER=true
WAO_JMS_CLOUD_ADDRESS=cloud.example
WAO_JMS_CLOUD_USER=cloud
ENV
cat >"$test_root/bin/ssh" <<'SH'
#!/bin/sh
printf '%s\n' "$*" >>"$TEST_LOG"
case "$*" in
  *slave@127.0.0.1*)
    case "$*" in *'-p 2201 '*) ;; *) exit 91 ;; esac
    [ "${FAIL_SLAVE:-false}" = false ] || exit 92 ;;
esac
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
grep -q 'three-host inventory' "$test_root/output"
test "$(grep -c 'manage.py shell' "$TEST_LOG")" -eq 3
grep -q 'WAO_JMS_SLAVE_PORT=2201' "$TEST_LOG"
grep -q 'verify_wao_ssh.py' "$TEST_LOG"
export FAIL_SLAVE=true
: >"$TEST_LOG"
if sh deploy/jumpserver/verify-assets.sh >"$test_root/output"; then exit 1; fi
! grep -q 'docker exec' "$TEST_LOG"
! grep -q 'three-host inventory' "$test_root/output"
export FAIL_SLAVE=false FAIL_CORE=true
: >"$TEST_LOG"
if sh deploy/jumpserver/verify-assets.sh >"$test_root/output"; then exit 1; fi
! grep -q 'verify_wao_sso.py' "$TEST_LOG"
! grep -q 'three-host inventory' "$test_root/output"
echo 'JumpServer tunnel probes and failure propagation passed.'
