#!/bin/sh
set -eu

state_root="${1:?state root is required}"
asset_private_key="${2:?asset private key path is required}"
asset_public_key="${3:?asset public key path is required}"

case "$state_root" in ''|/) echo "Unsafe JumpServer state root: $state_root" >&2; exit 2 ;; esac

if [ -f "$asset_private_key" ]; then
  test -s "$asset_public_key"
  exit 0
fi

# An interrupted deployment may leave the old fixed staging files at
# $state_root/wao-assets. A unique directory keeps reruns non-interactive and
# publishes the key pair only after both files have been generated.
key_work_dir="$(sudo -n -u wadi mktemp -d "$state_root/.wao-assets.XXXXXX")"
cleanup_key_work_dir() {
  if [ -n "${key_work_dir:-}" ]; then
    sudo -n -u wadi rm -rf -- "$key_work_dir"
  fi
}
trap cleanup_key_work_dir EXIT HUP INT TERM

staged_private_key="$key_work_dir/asset-key"
sudo -n -u wadi ssh-keygen -q -t ed25519 -N '' -C 'wao-jumpserver-managed' \
  -f "$staged_private_key"
sudo -n install -m 0600 -o root -g root "$staged_private_key" "$asset_private_key"
sudo -n install -m 0644 -o root -g root "$staged_private_key.pub" "$asset_public_key"

cleanup_key_work_dir
key_work_dir=''
trap - EXIT HUP INT TERM
