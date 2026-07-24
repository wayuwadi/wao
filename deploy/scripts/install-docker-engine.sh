#!/bin/sh
set -eu

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates cron curl
install -m 0755 -d /etc/apt/keyrings

install_ubuntu_packages() {
  rm -f /etc/apt/sources.list.d/docker.sources /etc/apt/keyrings/docker.asc
  apt-get update
  apt-get install -y docker.io docker-compose-v2
}

if curl -fsSL --retry 3 --connect-timeout 10 https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc; then
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  cat >/etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

  if ! apt-get update || ! apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin; then
    echo 'Docker official repository is unavailable; falling back to the Ubuntu distribution packages.' >&2
    install_ubuntu_packages
  fi
else
  echo 'Docker official repository is unavailable; falling back to the Ubuntu distribution packages.' >&2
  install_ubuntu_packages
fi
systemctl enable --now docker
systemctl enable --now cron

if [ -n "${SUDO_USER:-}" ] && [ "${SUDO_USER}" != 'root' ]; then
  usermod -aG docker "${SUDO_USER}"
  echo "Added ${SUDO_USER} to the docker group; start a new login session before deploying without sudo."
fi

docker version
docker compose version
