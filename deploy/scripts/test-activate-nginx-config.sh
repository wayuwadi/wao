#!/bin/sh
set -eu
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workspace="$(mktemp -d)"
trap 'rm -rf "${workspace}"' EXIT HUP INT TERM
project="${workspace}/project"
mkdir -p "${project}/deploy/scripts" "${project}/deploy/nginx" "${workspace}/bin" "${workspace}/run"
cp "${script_dir}/activate-nginx-config.sh" "${script_dir}/render-nginx-config.sh" \
  "${script_dir}/resolve-gateway-ipv4.sh" "${project}/deploy/scripts/"
cp "${script_dir}/../nginx/wao.wayuwa.cn.conf.template" "${project}/deploy/nginx/"
export WAO_NGINX_RUNTIME_DIR="${workspace}/run"
export WAO_NGINX_FINAL_CONFIG="${workspace}/wao.conf"
export WAO_NGINX_PENDING_CONFIG="${workspace}/pending.conf"
export WAO_NGINX_BOOTSTRAP_CONFIG="${workspace}/bootstrap.conf"
export WAO_NGINX_EXTERNAL_APPS_DIR="${workspace}/apps"
export MASTER_PUBLIC_HOST=master.test SLAVE_PUBLIC_HOST=slave.test
export WAO_TEST_DIR="${workspace}"
export PATH="${workspace}/bin:${PATH}"
cat >"${workspace}/bin/id" <<'SH'
#!/bin/sh
printf '0\n'
SH
cat >"${workspace}/bin/getent" <<'SH'
#!/bin/sh
case "$2" in
master.test) printf '%s STREAM master.test\n' "$(cat "$WAO_TEST_DIR/ip")" ;;
slave.test) printf '198.51.100.20 STREAM slave.test\n' ;;
*) exit 1 ;;
esac
SH
cat >"${workspace}/bin/curl" <<'SH'
#!/bin/sh
printf '{"code":0}\n'
SH
cat >"${workspace}/bin/nginx" <<'SH'
#!/bin/sh
[ ! -f "$WAO_TEST_DIR/invalid" ]
SH
cat >"${workspace}/bin/systemctl" <<'SH'
#!/bin/sh
printf '%s\n' "$*" >>"$WAO_TEST_DIR/reloads"
SH
chmod +x "${workspace}/bin/"*
printf '203.0.113.10\n' >"${workspace}/ip"
printf '# previous\n' >"${WAO_NGINX_FINAL_CONFIG}"
touch "${WAO_NGINX_RUNTIME_DIR}/slave.down" "${WAO_NGINX_RUNTIME_DIR}/deployment.lock"
activate() { sh "${project}/deploy/scripts/activate-nginx-config.sh"; }
activate
grep -Fq 'server 203.0.113.10:9000 backup max_fails' "${WAO_NGINX_FINAL_CONFIG}"
grep -Fq 'server 198.51.100.20:9000 down' "${WAO_NGINX_FINAL_CONFIG}"
printf '203.0.113.11\n' >"${workspace}/ip"
activate
grep -Fq 'server 203.0.113.11:9000 backup max_fails' "${WAO_NGINX_FINAL_CONFIG}"
grep -Fq 'server 198.51.100.20:9000 down' "${WAO_NGINX_FINAL_CONFIG}"
activate
test "$(wc -l <"${workspace}/reloads")" -eq 2
# An active state change owns the same lock; automatic refresh must defer.
(
  exec 9>"${WAO_NGINX_RUNTIME_DIR}/config.lock"
  flock 9
  printf '203.0.113.12\n' >"${workspace}/ip"
  activate
)
grep -Fq 'server 203.0.113.11:9000 backup max_fails' "${WAO_NGINX_FINAL_CONFIG}"
# Failed validation must restore the working configuration and avoid a reload.
touch "${workspace}/invalid"
if activate; then echo 'Invalid config unexpectedly activated.' >&2; exit 1; fi
grep -Fq 'server 203.0.113.11:9000 backup max_fails' "${WAO_NGINX_FINAL_CONFIG}"
test "$(wc -l <"${workspace}/reloads")" -eq 2
rm "${workspace}/invalid"
touch "${WAO_NGINX_RUNTIME_DIR}/master.down"
activate
test "$(wc -l <"${workspace}/reloads")" -eq 2
printf 'DDNS refresh preserves drained nodes and serializes config writes.\n'
