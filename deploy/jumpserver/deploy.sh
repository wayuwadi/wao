#!/bin/sh
set -eu

project_root="${1:?project root is required}"
case "$project_root" in ''|/) echo "Unsafe project root: $project_root" >&2; exit 2 ;; esac

version='v4.10.19'
edition_version='v4.10.19-ce'
archive="jumpserver-installer-${version}.tar.gz"
archive_md5='1d7b2534da5bc41ee34e6fda0053620b'
state_root="${JUMPSERVER_STATE_ROOT:-/opt/wao-jumpserver}"
installer_dir="$state_root/installer-$version"
config_dir="$state_root/config"
volume_dir="$state_root/data"
config_file="$config_dir/config.txt"
private_env="$project_root/deploy/.env.secrets"
asset_env="$state_root/assets.env"
asset_private_key="$volume_dir/core/data/bootstrap/wao-assets"
asset_public_key="$asset_private_key.pub"
published_public_key="$project_root/deploy/.jumpserver-asset-key.pub"

test -f "$private_env"
test -f "$project_root/deploy/jumpserver/custom_sso.py"
test -f "$project_root/deploy/jumpserver/bootstrap_wao_assets.py"
test -f "$project_root/deploy/jumpserver/verify_wao_access.py"

set -a
. "$project_root/deploy/.env"
. "$private_env"
set +a
: "${JUMPSERVER_SSO_SHARED_SECRET:?JumpServer SSO shared secret is required}"
if [ "${#JUMPSERVER_SSO_SHARED_SECRET}" -lt 32 ]; then
  echo 'JumpServer SSO shared secret is too short.' >&2
  exit 1
fi

validate_host() {
  case "$1" in *[!A-Za-z0-9.-]*|'') return 1 ;; esac
}
validate_user() {
  case "$1" in *[!A-Za-z0-9_.@-]*|'') return 1 ;; esac
}

master_address="${JUMPSERVER_MASTER_ADDRESS:-${MASTER_PUBLIC_HOST:-linux2.wayuwa.cn}}"
windows_address="${JUMPSERVER_WINDOWS_ADDRESS:-windows1.wayuwa.cn}"
cloud_address="${JUMPSERVER_CLOUD_ADDRESS:-8.136.152.131}"
master_user="${JUMPSERVER_MASTER_USER:-wadi}"
windows_user="${JUMPSERVER_WINDOWS_USER:-wadi}"
cloud_user="${JUMPSERVER_CLOUD_USER:-root}"
for host in "$master_address" "$windows_address" "$cloud_address"; do validate_host "$host"; done
for user in "$master_user" "$windows_user" "$cloud_user"; do validate_user "$user"; done

sudo -n install -d -m 0750 -o wadi -g wadi "$state_root" "$config_dir" "$volume_dir"
if [ ! -x "$installer_dir/jmsctl.sh" ]; then
  download_dir="$(mktemp -d)"
  curl --fail --silent --show-error --location --retry 3 --retry-all-errors \
    "https://resource.fit2cloud.com/jumpserver/installer/releases/download/$version/$archive" \
    -o "$download_dir/$archive"
  printf '%s  %s\n' "$archive_md5" "$download_dir/$archive" | md5sum -c -
  sudo -n install -d -m 0755 "$installer_dir"
  sudo -n tar -xzf "$download_dir/$archive" --strip-components=1 -C "$installer_dir"
fi

random_hex() {
  openssl rand -hex "$1"
}

if [ ! -f "$config_file" ]; then
  db_password="$(random_hex 24)"
  redis_password="$(random_hex 24)"
  secret_key="$(random_hex 32)"
  bootstrap_token="$(random_hex 24)"
  umask 077
  config_tmp="$(mktemp)"
  {
    printf 'VOLUME_DIR=%s\n' "$volume_dir"
    printf 'SECRET_KEY=%s\n' "$secret_key"
    printf 'BOOTSTRAP_TOKEN=%s\n' "$bootstrap_token"
    printf 'LOG_LEVEL=ERROR\n'
    printf 'DOCKER_SUBNET=172.31.250.0/24\n'
    printf 'USE_IPV6=0\n'
    printf 'DB_ENGINE=postgresql\nDB_HOST=postgresql\nDB_PORT=5432\nDB_USER=jumpserver\n'
    printf 'DB_PASSWORD=%s\nDB_NAME=jumpserver\n' "$db_password"
    printf 'REDIS_HOST=redis\nREDIS_PORT=6379\nREDIS_PASSWORD=%s\n' "$redis_password"
    printf 'HTTP_PORT=127.0.0.1:18080\nCLIENT_MAX_BODY_SIZE=4096m\n'
    printf 'CORE_HOST=http://core:8080\nPERIOD_TASK_ENABLED=true\n'
    printf 'SESSION_EXPIRE_AT_BROWSER_CLOSE=false\nSESSION_COOKIE_AGE=604800\n'
    printf 'SESSION_COOKIE_SECURE=true\nCSRF_COOKIE_SECURE=true\n'
    printf 'DOMAINS=jms.wayuwa.cn:443\nBASE_SITE_URL=https://jms.wayuwa.cn\n'
    printf 'USE_LB=1\nTZ=Asia/Shanghai\nCURRENT_VERSION=%s\n' "$edition_version"
    printf 'DOCKER_IMAGE_MIRROR=1\n'
    printf 'USE_XPACK=0\nKOTL_ENABLED=0\nKOKO_SSH_PORT=127.0.0.1:2222\n'
    printf 'AUTH_CUSTOM_SSO=true\nAUTH_CUSTOM_SSO_QUERY_PARAMS=token\n'
    printf 'JUMPSERVER_SSO_SHARED_SECRET=%s\n' "$JUMPSERVER_SSO_SHARED_SECRET"
  } >"$config_tmp"
  sudo -n install -m 0600 -o root -g root "$config_tmp" "$config_file"
fi

sudo -n install -d -m 0750 "$config_dir/redis" "$volume_dir/core/data/auth" "$volume_dir/core/data/bootstrap"
sudo -n install -m 0644 "$installer_dir/config_init/redis/redis.conf" "$config_dir/redis/redis.conf"
sudo -n install -m 0644 "$project_root/deploy/jumpserver/custom_sso.py" \
  "$volume_dir/core/data/auth/custom_sso.py"
sudo -n install -m 0644 "$project_root/deploy/jumpserver/bootstrap_wao_assets.py" \
  "$volume_dir/core/data/bootstrap/bootstrap_wao_assets.py"
sudo -n install -m 0644 "$project_root/deploy/jumpserver/verify_wao_access.py" \
  "$volume_dir/core/data/bootstrap/verify_wao_access.py"

set_config() {
  key="$1"
  value="$2"
  if sudo -n grep -q "^${key}=" "$config_file"; then
    sudo -n sed -i "s#^${key}=.*#${key}=${value}#" "$config_file"
  else
    printf '%s=%s\n' "$key" "$value" | sudo -n tee -a "$config_file" >/dev/null
  fi
}

custom_sso_md5="$(md5sum "$project_root/deploy/jumpserver/custom_sso.py" | awk '{print $1}')"
set_config CURRENT_VERSION "$edition_version"
set_config DOCKER_IMAGE_MIRROR 1
set_config AUTH_CUSTOM_SSO true
set_config AUTH_CUSTOM_SSO_QUERY_PARAMS token
set_config AUTH_CUSTOM_SSO_FILE_MD5 "$custom_sso_md5"
set_config JUMPSERVER_SSO_SHARED_SECRET "$JUMPSERVER_SSO_SHARED_SECRET"
set_config DOMAINS 'jms.wayuwa.cn:443'
set_config BASE_SITE_URL 'https://jms.wayuwa.cn'
set_config HTTP_PORT '127.0.0.1:18080'

sh "$project_root/deploy/jumpserver/ensure-asset-key.sh" \
  "$state_root" "$asset_private_key" "$asset_public_key"
install -d -m 0700 "$HOME/.ssh"
touch "$HOME/.ssh/authorized_keys"
chmod 0600 "$HOME/.ssh/authorized_keys"
public_key="$(sudo -n sed -n '1p' "$asset_public_key")"
grep -qxF "$public_key" "$HOME/.ssh/authorized_keys" || printf '%s\n' "$public_key" >>"$HOME/.ssh/authorized_keys"
printf '%s\n' "$public_key" >"$published_public_key"
chmod 0644 "$published_public_key"

asset_env_tmp="$(mktemp)"
printf '%s\n' \
  "WAO_JMS_MASTER_ADDRESS=$master_address" "WAO_JMS_MASTER_USER=$master_user" \
  "WAO_JMS_WINDOWS_ADDRESS=$windows_address" "WAO_JMS_WINDOWS_USER=$windows_user" \
  "WAO_JMS_CLOUD_ADDRESS=$cloud_address" "WAO_JMS_CLOUD_USER=$cloud_user" >"$asset_env_tmp"
sudo -n install -m 0600 -o wadi -g wadi "$asset_env_tmp" "$asset_env"

run_jmsctl() {
  sudo -n env JS_CONFIG_DIR="$config_dir" UNCHECK_DEPENDENCIES=1 \
    bash "$installer_dir/jmsctl.sh" "$@"
}

schema_marker="$state_root/schema-$edition_version"
run_jmsctl start --skip-kotl
if [ ! -f "$schema_marker" ]; then
  run_jmsctl migrate_db
  sudo -n install -m 0644 /dev/null "$schema_marker"
  run_jmsctl restart --skip-kotl
fi

attempt=1
until curl --fail --silent --show-error --max-time 10 http://127.0.0.1:18080/api/health/ >/dev/null; do
  if [ "$attempt" -ge 30 ]; then
    run_jmsctl status --skip-kotl || true
    echo 'JumpServer health check did not become ready.' >&2
    exit 1
  fi
  sleep 10
  attempt=$((attempt + 1))
done

set -a
. "$asset_env"
set +a
sudo -n docker exec \
  -e "WAO_JMS_MASTER_ADDRESS=$WAO_JMS_MASTER_ADDRESS" \
  -e "WAO_JMS_MASTER_USER=$WAO_JMS_MASTER_USER" \
  -e "WAO_JMS_WINDOWS_ADDRESS=$WAO_JMS_WINDOWS_ADDRESS" \
  -e "WAO_JMS_WINDOWS_USER=$WAO_JMS_WINDOWS_USER" \
  -e "WAO_JMS_CLOUD_ADDRESS=$WAO_JMS_CLOUD_ADDRESS" \
  -e "WAO_JMS_CLOUD_USER=$WAO_JMS_CLOUD_USER" \
  -i jms_core sh -c \
  'python /opt/jumpserver/apps/manage.py shell < /opt/jumpserver/data/bootstrap/bootstrap_wao_assets.py'

sudo -n install -m 0644 "$project_root/deploy/jumpserver/wao-jumpserver-tunnel.service" \
  /etc/systemd/system/wao-jumpserver-tunnel.service
sudo -n systemctl daemon-reload
sudo -n systemctl enable --now wao-jumpserver-tunnel.service
sudo -n systemctl restart wao-jumpserver-tunnel.service
sudo -n systemctl is-active --quiet wao-jumpserver-tunnel.service

echo "JumpServer $edition_version is healthy and WAO assets are registered."
