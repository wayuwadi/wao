#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT HUP INT TERM

cat >"${tmp_dir}/getent" <<'EOF'
#!/bin/sh
[ "$1" = 'ahostsv4' ] || exit 1
case "$2" in
  master.test)
    printf '%s\n' '203.0.113.10 STREAM master.test' '203.0.113.10 DGRAM master.test'
    ;;
  *)
    exit 2
    ;;
esac
EOF
chmod 0755 "${tmp_dir}/getent"

rendered="${tmp_dir}/wao.conf"
PATH="${tmp_dir}:${PATH}" \
  MASTER_PUBLIC_HOST='master.test' \
  WAO_NGINX_RENDER_OUTPUT="${rendered}" \
  sh "${script_dir}/render-nginx-config.sh"

grep -q 'server 203.0.113.10:9000 max_fails=3' "${rendered}"
if grep -A 5 '^upstream wao_gateway {' "${rendered}" | grep -Eq 'backup|SLAVE_GATEWAY|10\.66\.0\.3'; then
  echo 'Rendered Gateway upstream must contain only the Linux2 address.' >&2
  exit 1
fi
grep -q 'log_format wao_ready_upstream' "${rendered}"
grep -q 'access_log /var/log/nginx/access.log combined;' "${rendered}"
grep -q 'access_log /var/log/nginx/wao-ready-upstream.log wao_ready_upstream if=\$wao_ready_trace;' "${rendered}"
if grep -q 'location = /game {' "${rendered}"; then
  echo 'Retired game route must not be restored.' >&2
  exit 1
fi
grep -q 'client_max_body_size 1030m;' "${rendered}"
grep -q 'location = /625word {' "${rendered}"
grep -q 'return 302 /api/pages/625word;' "${rendered}"
grep -q 'location = /insight {' "${rendered}"
grep -q 'return 410;' "${rendered}"
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' "${rendered}"
grep -q 'Cache-Control "no-cache, no-store, must-revalidate"' "${rendered}"
grep -q 'location = /amer_demo_4 {' "${rendered}"
grep -q 'return 308 /amer_demo_4/;' "${rendered}"
grep -q 'location = /amer_demo_5 {' "${rendered}"
grep -q 'return 308 /amer_demo_5/;' "${rendered}"
grep -A 3 'location \^~ /amer_demo_5/ {' "${rendered}" | grep -q 'try_files \$uri \$uri/ =404;'
grep -q 'location = /amer_demo_6 {' "${rendered}"
grep -q 'return 308 /amer_demo_6/;' "${rendered}"
grep -A 3 'location \^~ /amer_demo_6/ {' "${rendered}" | grep -q 'try_files \$uri \$uri/ =404;'
grep -q 'location = /amer_demo_7 {' "${rendered}"
grep -q 'return 308 /amer_demo_7/;' "${rendered}"
grep -A 3 'location \^~ /amer_demo_7/ {' "${rendered}" | grep -q 'try_files \$uri \$uri/ =404;'
grep -A 3 'location \^~ /amer_demo_4/ {' "${rendered}" | grep -q 'try_files \$uri \$uri/ =404;'
grep -A 4 'location = /downloads/desktop/client/WAO-macos-universal.dmg {' "${rendered}" | grep -q 'no-store'
unexpected_upstreams="$(awk '$1 == "server" && $2 ~ /:9000/ && $2 !~ /^(203\.0\.113\.10|198\.51\.100\.20|127\.0\.0\.1):9000/ { print }' "${rendered}")"
if [ -n "${unexpected_upstreams}" ]; then
  echo 'Rendered Nginx upstreams must contain IPv4 literals only.' >&2
  printf '%s\n' "${unexpected_upstreams}" >&2
  exit 1
fi

if PATH="${tmp_dir}:${PATH}" \
  MASTER_PUBLIC_HOST='missing.test' \
  WAO_NGINX_RENDER_OUTPUT="${tmp_dir}/missing.conf" \
  sh "${script_dir}/render-nginx-config.sh" >/dev/null 2>&1; then
  echo 'Rendering must fail when a Gateway has no IPv4 address.' >&2
  exit 1
fi

echo 'Nginx IPv4 upstream rendering tests passed.'
