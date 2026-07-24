#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo 'Run this script as root.' >&2
  exit 1
fi

. /etc/os-release
if [ "${ID:-}" != 'ubuntu' ] || [ -z "${VERSION_CODENAME:-}" ]; then
  echo 'This installer currently supports Ubuntu with VERSION_CODENAME.' >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl

keyring_url="https://pkgs.tailscale.com/stable/ubuntu/${VERSION_CODENAME}.noarmor.gpg"
source_url="https://pkgs.tailscale.com/stable/ubuntu/${VERSION_CODENAME}.tailscale-keyring.list"
curl -fsSL "${keyring_url}" -o /usr/share/keyrings/tailscale-archive-keyring.gpg
curl -fsSL "${source_url}" -o /etc/apt/sources.list.d/tailscale.list

apt-get update
apt-get install -y tailscale
systemctl enable --now tailscaled

echo 'Tailscale is installed. Authenticate this node with: tailscale up'
