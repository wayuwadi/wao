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
env_file="${deploy_dir}/.env"

test -f "${env_file}" || { echo "Missing ${env_file}" >&2; exit 1; }
set -a
. "${env_file}"
set +a
: "${CLOUD_TAILSCALE_IP:?CLOUD_TAILSCALE_IP is required}"

actual_tailscale_ip="$(tailscale ip -4)"
if [ "${actual_tailscale_ip}" != "${CLOUD_TAILSCALE_IP}" ]; then
  echo "Tailscale IP mismatch: expected ${CLOUD_TAILSCALE_IP}, got ${actual_tailscale_ip}" >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y certbot curl gettext-base nginx
install -d -m 0755 /var/www/wao/dist /var/www/certbot

certificate_dir='/etc/letsencrypt/live/wao.wayuwa.cn'
bootstrap_config='/etc/nginx/conf.d/wao.bootstrap.conf'
final_config='/etc/nginx/conf.d/wao.wayuwa.cn.conf'

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

sh "${script_dir}/render-nginx-config.sh"
install -m 0644 "${deploy_dir}/nginx/wao.wayuwa.cn.conf" "${final_config}"
rm -f "${bootstrap_config}"

renewal_hook='/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh'
install -d -m 0755 "$(dirname -- "${renewal_hook}")"
printf '%s\n' '#!/bin/sh' 'set -eu' 'nginx -t' 'systemctl reload nginx' >"${renewal_hook}"
chmod 0755 "${renewal_hook}"

nginx -t
systemctl enable --now nginx
systemctl reload nginx
sh "${script_dir}/deploy-linux.sh" cloud-sentinel

cron_file='/etc/cron.d/wao-health'
printf '%s\n' \
  'SHELL=/bin/sh' \
  'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin' \
  "* * * * * root set -a; . ${env_file}; set +a; ${script_dir}/health-check.sh" \
  >"${cron_file}"
chmod 0644 "${cron_file}"

certbot renew --dry-run
echo 'Cloud node provisioning completed.'
