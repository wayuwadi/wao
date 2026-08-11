#!/bin/sh
set -eu

: "${1:?Usage: provision-cloud-node.sh <acme-email>}"
acme_email="$1"

if [ "$(id -u)" -ne 0 ]; then
  echo 'Run this script as root.' >&2
  exit 1
fi

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"
deploy_dir="${project_root}/deploy"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y certbot curl gettext-base nginx
install -d -m 0755 /var/www/wao/dist /var/www/certbot

certificate_dir='/etc/letsencrypt/live/wao.wayuwa.cn'
bootstrap_config='/etc/nginx/conf.d/wao.bootstrap.conf'

if [ ! -f "${certificate_dir}/fullchain.pem" ]; then
  install -m 0644 "${deploy_dir}/nginx/wao.bootstrap.conf" "${bootstrap_config}"
  nginx -t
  systemctl enable --now nginx
  systemctl reload nginx
  certbot certonly \
    --non-interactive \
    --agree-tos \
    --email "${acme_email}" \
    --webroot \
    --webroot-path /var/www/certbot \
    --domain wao.wayuwa.cn
fi

renewal_hook='/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh'
install -d -m 0755 "$(dirname -- "${renewal_hook}")"
printf '%s\n' '#!/bin/sh' 'set -eu' 'nginx -t' 'systemctl reload nginx' >"${renewal_hook}"
chmod 0755 "${renewal_hook}"

sh "${script_dir}/activate-nginx-config.sh"
systemctl enable nginx
cron_file='/etc/cron.d/wao-health'
printf '%s\n' \
  'SHELL=/bin/sh' \
  'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
  "* * * * * root ${script_dir}/health-check.sh" \
  >"${cron_file}"
chmod 0644 "${cron_file}"

ddns_reload_cron='/etc/cron.d/wao-nginx-ddns-reload'
printf '%s\n' \
  'SHELL=/bin/sh' \
  'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
  "* * * * * root ${script_dir}/activate-nginx-config.sh >/dev/null 2>&1" \
  >"${ddns_reload_cron}"
chmod 0644 "${ddns_reload_cron}"

certbot renew --dry-run
echo 'Cloud node provisioning completed.'
