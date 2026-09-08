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
  slave.test)
    printf '%s\n' '198.51.100.20 STREAM slave.test' '198.51.100.20 DGRAM slave.test'
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
  SLAVE_PUBLIC_HOST='slave.test' \
  WAO_NGINX_RENDER_OUTPUT="${rendered}" \
  sh "${script_dir}/render-nginx-config.sh"

grep -q 'server 203.0.113.10:9000' "${rendered}"
grep -q 'server 198.51.100.20:9000' "${rendered}"
grep -q 'location = /game {' "${rendered}"
grep -q 'return 308 /games;' "${rendered}"
grep -q 'location = /insight {' "${rendered}"
grep -q 'return 410;' "${rendered}"
grep -q 'location = /pluginWebUpdateNotice/web_version_by_plugin.json' "${rendered}"
grep -q 'Cache-Control "no-cache, no-store, must-revalidate"' "${rendered}"
grep -q 'location = /amer_demo_4 {' "${rendered}"
grep -q 'return 308 /amer_demo_4/;' "${rendered}"
grep -q 'location = /amer_demo_5 {' "${rendered}"
grep -q 'return 308 /amer_demo_5/;' "${rendered}"
grep -A 3 'location \^~ /amer_demo_5/ {' "${rendered}" | grep -q 'try_files \$uri \$uri/ =404;'
grep -A 3 'location \^~ /amer_demo_4/ {' "${rendered}" | grep -q 'try_files \$uri \$uri/ =404;'
unexpected_upstreams="$(awk '$1 == "server" && $2 ~ /:9000/ && $2 !~ /^(203\.0\.113\.10|198\.51\.100\.20):9000/ { print }' "${rendered}")"
if [ -n "${unexpected_upstreams}" ]; then
  echo 'Rendered Nginx upstreams must contain IPv4 literals only.' >&2
  printf '%s\n' "${unexpected_upstreams}" >&2
  exit 1
fi

MASTER_GATEWAY_STATE=' down' \
  SLAVE_GATEWAY_STATE='' \
  MASTER_GATEWAY_IPV4='203.0.113.10' \
  SLAVE_GATEWAY_IPV4='198.51.100.20' \
  WAO_NGINX_RENDER_OUTPUT="${tmp_dir}/master-down.conf" \
  sh "${script_dir}/render-nginx-config.sh"
grep -q 'server 203.0.113.10:9000 down max_fails=3' "${tmp_dir}/master-down.conf"
grep -q 'server 198.51.100.20:9000 max_fails=3' "${tmp_dir}/master-down.conf"

if MASTER_GATEWAY_STATE='invalid' \
  MASTER_GATEWAY_IPV4='203.0.113.10' \
  SLAVE_GATEWAY_IPV4='198.51.100.20' \
  WAO_NGINX_RENDER_OUTPUT="${tmp_dir}/invalid-state.conf" \
  sh "${script_dir}/render-nginx-config.sh" >/dev/null 2>&1; then
  echo 'Rendering must reject an invalid Gateway state.' >&2
  exit 1
fi

if PATH="${tmp_dir}:${PATH}" \
  MASTER_PUBLIC_HOST='missing.test' \
  SLAVE_PUBLIC_HOST='slave.test' \
  WAO_NGINX_RENDER_OUTPUT="${tmp_dir}/missing.conf" \
  sh "${script_dir}/render-nginx-config.sh" >/dev/null 2>&1; then
  echo 'Rendering must fail when a Gateway has no IPv4 address.' >&2
  exit 1
fi

echo 'Nginx IPv4 upstream rendering tests passed.'
