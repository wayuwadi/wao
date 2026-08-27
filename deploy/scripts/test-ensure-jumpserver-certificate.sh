#!/bin/sh
set -eu

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

bin_dir="$test_root/bin"
certificate_dir="$test_root/certificates"
bootstrap="$test_root/jms.bootstrap.conf"
webroot="$test_root/webroot"
calls="$test_root/certbot.calls"
script="$test_root/ensure-certificate.sh"
mkdir -p "$bin_dir" "$certificate_dir" "$webroot"

sed \
  -e "s|certificate_dir='/etc/letsencrypt/live/jms.wayuwa.cn'|certificate_dir='$certificate_dir'|" \
  -e "s|bootstrap='/etc/nginx/conf.d/jms.bootstrap.conf'|bootstrap='$bootstrap'|" \
  -e "s|install -d -m 0755 /var/www/certbot|install -d -m 0755 '$webroot'|" \
  -e "s|--webroot-path /var/www/certbot|--webroot-path '$webroot'|" \
  deploy/scripts/ensure-jumpserver-certificate.sh >"$script"
chmod +x "$script"

cat >"$bin_dir/getent" <<'EOF'
#!/bin/sh
printf '203.0.113.10 STREAM jms.wayuwa.cn\n'
EOF

cat >"$bin_dir/openssl" <<'EOF'
#!/bin/sh
certificate=''
while [ "$#" -gt 0 ]; do
  if [ "$1" = -in ]; then
    certificate="$2"
    shift 2
    continue
  fi
  shift
done
grep -q 'DNS:jms.wayuwa.cn' "$certificate"
EOF

cat >"$bin_dir/nginx" <<'EOF'
#!/bin/sh
exit 0
EOF

cat >"$bin_dir/systemctl" <<'EOF'
#!/bin/sh
exit 0
EOF

cat >"$bin_dir/certbot" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$CERTBOT_CALLS"
printf 'DNS:jms.wayuwa.cn\n' >"$CERTBOT_CERTIFICATE_DIR/fullchain.pem"
printf 'private key\n' >"$CERTBOT_CERTIFICATE_DIR/privkey.pem"
EOF
chmod +x "$bin_dir"/*

run_script() {
  CERTBOT_CALLS="$calls" CERTBOT_CERTIFICATE_DIR="$certificate_dir" \
    PATH="$bin_dir:$PATH" sh "$script" 203.0.113.10
}

printf 'DNS:jms.wayuwa.cn\n' >"$certificate_dir/fullchain.pem"
printf 'private key\n' >"$certificate_dir/privkey.pem"
run_script
test ! -e "$calls"

printf 'DNS:wao.wayuwa.cn\n' >"$certificate_dir/fullchain.pem"
run_script
grep -q -- '--force-renewal' "$calls"
test ! -e "$bootstrap"

rm -f "$certificate_dir/fullchain.pem" "$certificate_dir/privkey.pem" "$calls"
run_script
! grep -q -- '--force-renewal' "$calls"
grep -q -- '--domain jms.wayuwa.cn' "$calls"
test ! -e "$bootstrap"

echo 'JumpServer certificate domain validation tests passed.'
