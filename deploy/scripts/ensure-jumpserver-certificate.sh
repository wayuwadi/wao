#!/bin/sh
set -eu

expected_ipv4="${1:?expected cloud IPv4 is required}"
case "$expected_ipv4" in *[!0-9.]*|'') echo 'Invalid expected cloud IPv4.' >&2; exit 2 ;; esac

resolved="$(getent ahostsv4 jms.wayuwa.cn | awk '$2 == "STREAM" {print $1; exit}')"
if [ "$resolved" != "$expected_ipv4" ]; then
  printf 'jms.wayuwa.cn resolves to %s, expected %s.\n' "${resolved:-nothing}" "$expected_ipv4" >&2
  exit 1
fi

certificate_dir='/etc/letsencrypt/live/jms.wayuwa.cn'
if [ -s "$certificate_dir/fullchain.pem" ] && [ -s "$certificate_dir/privkey.pem" ]; then
  exit 0
fi

bootstrap='/etc/nginx/conf.d/jms.bootstrap.conf'
cleanup() {
  rm -f "$bootstrap"
  nginx -t >/dev/null 2>&1 && systemctl reload nginx || true
}
trap cleanup EXIT HUP INT TERM

install -d -m 0755 /var/www/certbot
printf '%s\n' \
  'server {' \
  '    listen 80;' \
  '    listen [::]:80;' \
  '    server_name jms.wayuwa.cn;' \
  '    location /.well-known/acme-challenge/ { root /var/www/certbot; }' \
  '    location / { return 503; }' \
  '}' >"$bootstrap"
nginx -t
systemctl reload nginx

certbot certonly \
  --non-interactive \
  --agree-tos \
  --register-unsafely-without-email \
  --webroot \
  --webroot-path /var/www/certbot \
  --cert-name jms.wayuwa.cn \
  --domain jms.wayuwa.cn

test -s "$certificate_dir/fullchain.pem"
test -s "$certificate_dir/privkey.pem"
trap - EXIT HUP INT TERM
cleanup
echo 'JumpServer TLS certificate is ready.'
