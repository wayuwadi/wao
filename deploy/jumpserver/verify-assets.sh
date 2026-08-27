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
verify_ssh 'Windows node' "$WAO_JMS_WINDOWS_USER" "$WAO_JMS_WINDOWS_ADDRESS"
verify_ssh 'Cloud ingress' "$WAO_JMS_CLOUD_USER" "$WAO_JMS_CLOUD_ADDRESS"

test "$(sudo -n docker exec jms_core python manage.py shell -c \
  'from assets.models import Host; print(Host.objects.filter(name__startswith="WAO ").count())' \
  | tail -n 1)" = 3
test "$(sudo -n docker exec jms_core python manage.py shell -c \
  'from accounts.models import Account; print(Account.objects.filter(name="wao-jumpserver").count())' \
  | tail -n 1)" = 3
echo 'JumpServer three-host inventory and dedicated accounts verified.'
