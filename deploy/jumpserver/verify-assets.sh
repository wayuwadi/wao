#!/bin/sh
set -eu

state_root="${JUMPSERVER_STATE_ROOT:-/opt/wao-jumpserver}"
asset_env="$state_root/assets.env"
private_key="$state_root/data/core/data/bootstrap/wao-assets"
known_hosts="$state_root/asset-known-hosts"
test -f "$asset_env"
test -f "$private_key"

set -a
. "$asset_env"
set +a

verify_ssh() {
  label="$1"
  user="$2"
  host="$3"
  ssh -i "$private_key" -o BatchMode=yes -o ConnectTimeout=12 \
    -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$known_hosts" \
    "$user@$host" exit
  printf '%s SSH access verified.\n' "$label"
}

verify_ssh 'Linux master' "$WAO_JMS_MASTER_USER" "$WAO_JMS_MASTER_ADDRESS"
verify_ssh 'Linux slave' "$WAO_JMS_SLAVE_USER" "$WAO_JMS_SLAVE_ADDRESS"
verify_ssh 'Cloud ingress' "$WAO_JMS_CLOUD_USER" "$WAO_JMS_CLOUD_ADDRESS"

sudo -n docker exec -i jms_core sh -c \
  'python /opt/jumpserver/apps/manage.py shell < /opt/jumpserver/data/bootstrap/verify_wao_access.py'
echo 'JumpServer three-host inventory, accounts, protocols, and dual-member grants verified.'
