#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workspace="$(mktemp -d)"
trap 'rm -rf "${workspace}"' EXIT HUP INT TERM

project="${workspace}/project"
fake_bin="${workspace}/bin"
runtime_dir="${workspace}/run"
final_config="${workspace}/wao.conf"
process_state="${workspace}/process-state"
kill_log="${workspace}/kill.log"
mkdir -p "${project}/deploy/scripts" "${project}/deploy/nginx" "${fake_bin}" "${runtime_dir}"
cp "${script_dir}/set-nginx-upstream-state.sh" "${project}/deploy/scripts/"
cp "${script_dir}/render-nginx-config.sh" "${project}/deploy/scripts/"
cp "${script_dir}/resolve-gateway-ipv4.sh" "${project}/deploy/scripts/"
cp "${script_dir}/../nginx/wao.wayuwa.cn.conf.template" "${project}/deploy/nginx/"
printf '%s\n' '# initial config' >"${final_config}"
printf '%s\n' old-workers >"${process_state}"

cat >"${fake_bin}/id" <<'SH'
#!/bin/sh
printf '%s\n' 0
SH
cat >"${fake_bin}/nginx" <<'SH'
#!/bin/sh
if [ "${WAO_NGINX_TEST_FAIL_NEXT:-false}" = true ] && [ ! -f "${WAO_NGINX_TEST_FAIL_MARKER}" ]; then
  touch "${WAO_NGINX_TEST_FAIL_MARKER}"
  exit 1
fi
exit 0
SH
cat >"${fake_bin}/flock" <<'SH'
#!/bin/sh
exit 0
SH
cat >"${fake_bin}/envsubst" <<'SH'
#!/bin/sh
python3 -c '
import os, sys
text = sys.stdin.read()
for name in ("MASTER_GATEWAY_IPV4", "SLAVE_GATEWAY_IPV4", "SLAVE_GATEWAY_TUNNEL_IPV4", "MASTER_GATEWAY_STATE", "SLAVE_GATEWAY_STATE"):
    text = text.replace("${" + name + "}", os.environ.get(name, ""))
sys.stdout.write(text)
'
SH
cat >"${fake_bin}/systemctl" <<'SH'
#!/bin/sh
test "$1" = reload
test "$2" = nginx
SH
cat >"${fake_bin}/ps" <<'SH'
#!/bin/sh
set -eu
if [ "$1" = -eo ]; then
  printf '%s\n' '200 100 nginx: worker process'
  exit 0
fi
if [ "$1" = -o ] && [ "$2" = ppid= ] && [ "$3" = -p ] && [ "$4" = 200 ]; then
  if [ "$(cat "${WAO_NGINX_TEST_PROCESS_STATE}")" = old-workers ]; then
    printf '%s\n' 100
  fi
  exit 0
fi
exit 1
SH
cat >"${fake_bin}/kill" <<'SH'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"${WAO_NGINX_TEST_KILL_LOG}"
printf '%s\n' drained >"${WAO_NGINX_TEST_PROCESS_STATE}"
SH
chmod +x "${fake_bin}"/*

# The production script reads the standard Nginx master PID location. Tests
# override cat only for that exact path and delegate every other read.
cat >"${fake_bin}/cat" <<'SH'
#!/bin/sh
if [ "${1:-}" = /run/nginx.pid ]; then
  printf '%s\n' 100
else
  /bin/cat "$@"
fi
SH
chmod +x "${fake_bin}/cat"

PATH="${fake_bin}:${PATH}" \
MASTER_GATEWAY_IPV4=203.0.113.10 SLAVE_GATEWAY_IPV4=198.51.100.20 \
WAO_NGINX_RUNTIME_DIR="${runtime_dir}" \
WAO_NGINX_FINAL_CONFIG="${final_config}" \
WAO_NGINX_WORKER_DRAIN_SECONDS=0 \
WAO_NGINX_PS_COMMAND="${fake_bin}/ps" \
WAO_NGINX_KILL_COMMAND="${fake_bin}/kill" \
WAO_NGINX_TEST_PROCESS_STATE="${process_state}" \
WAO_NGINX_TEST_KILL_LOG="${kill_log}" \
sh "${project}/deploy/scripts/set-nginx-upstream-state.sh" master down

grep -Fq '203.0.113.10:9000 down' "${final_config}"
grep -Fq '127.0.0.1:9000 backup max_fails' "${final_config}"

if PATH="${fake_bin}:${PATH}" \
    MASTER_GATEWAY_IPV4=invalid SLAVE_GATEWAY_IPV4=198.51.100.20 \
    WAO_NGINX_RUNTIME_DIR="${runtime_dir}" \
    WAO_NGINX_FINAL_CONFIG="${final_config}" \
    WAO_NGINX_WORKER_DRAIN_SECONDS=0 \
    sh "${project}/deploy/scripts/set-nginx-upstream-state.sh" master up >/dev/null 2>&1; then
  echo 'A failed Nginx render must fail.' >&2
  exit 1
fi
test -f "${runtime_dir}/master.down"
grep -Fq '203.0.113.10:9000 down' "${final_config}"
grep -Fq '198.51.100.20:9000 max_fails' "${final_config}"
grep -Fxq -- '-TERM 200' "${kill_log}"
test -f "${runtime_dir}/master.down"
test -f "${runtime_dir}/deployment.lock"

# A rejected Nginx config must restore both the previous file and route marker.
if PATH="${fake_bin}:${PATH}" \
    MASTER_GATEWAY_IPV4=203.0.113.10 SLAVE_GATEWAY_IPV4=198.51.100.20 \
    WAO_NGINX_RUNTIME_DIR="${runtime_dir}" \
    WAO_NGINX_FINAL_CONFIG="${final_config}" \
    WAO_NGINX_WORKER_DRAIN_SECONDS=0 \
    WAO_NGINX_PS_COMMAND="${fake_bin}/ps" \
    WAO_NGINX_TEST_FAIL_NEXT=true \
    WAO_NGINX_TEST_FAIL_MARKER="${workspace}/nginx-failed" \
    sh "${project}/deploy/scripts/set-nginx-upstream-state.sh" master up >/dev/null 2>&1; then
  echo 'A rejected Nginx config must fail.' >&2
  exit 1
fi
test -f "${runtime_dir}/master.down"
grep -Fq '203.0.113.10:9000 down' "${final_config}"

if PATH="${fake_bin}:${PATH}" \
    WAO_NGINX_RUNTIME_DIR="${runtime_dir}" \
    WAO_NGINX_FINAL_CONFIG="${final_config}" \
    WAO_NGINX_WORKER_DRAIN_SECONDS=invalid \
    sh "${project}/deploy/scripts/set-nginx-upstream-state.sh" master up >/dev/null 2>&1; then
  echo 'An invalid worker drain timeout must fail closed.' >&2
  exit 1
fi

echo 'Nginx upstream state worker drain tests passed.'
