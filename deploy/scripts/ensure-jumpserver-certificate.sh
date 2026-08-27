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
certificate_matches_domain() {
  [ -s "$certificate_dir/fullchain.pem" ] &&
    [ -s "$certificate_dir/privkey.pem" ] &&
    openssl x509 -in "$certificate_dir/fullchain.pem" -noout \
      -checkhost jms.wayuwa.cn >/dev/null 2>&1
}

if certificate_matches_domain; then
  exit 0
fi

force_renewal=false
if [ -e "$certificate_dir/fullchain.pem" ] || [ -e "$certificate_dir/privkey.pem" ]; then
  force_renewal=true
  echo 'Existing JumpServer certificate does not cover jms.wayuwa.cn; requesting a replacement.' >&2
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

set -- certbot certonly \
  --non-interactive \
  --agree-tos \
  --register-unsafely-without-email \
  --webroot \
  --webroot-path /var/www/certbot \
  --cert-name jms.wayuwa.cn \
  --domain jms.wayuwa.cn
if [ "$force_renewal" = true ]; then
  set -- "$@" --force-renewal
fi
"$@"

if ! certificate_matches_domain; then
  echo 'Issued certificate does not cover jms.wayuwa.cn.' >&2
  exit 1
fi
trap - EXIT HUP INT TERM
cleanup
echo 'JumpServer TLS certificate is ready.'
