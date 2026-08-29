#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo 'Run prepare-controller-secrets.sh as root.' >&2
  exit 1
fi

secret_dir='/etc/jenkins/secrets'
install -d -m 0750 "$secret_dir"

generate_secret() {
  target="$1"
  if [ ! -s "$target" ]; then
    umask 077
    openssl rand -base64 36 >"$target"
  fi
}

generate_key() {
  target="$1"
  if [ ! -s "$target" ]; then
    ssh-keygen -q -t ed25519 -N '' -C 'wao-jenkins-deploy' -f "$target"
  fi
}

generate_secret "$secret_dir/admin-password"
generate_secret "$secret_dir/github-webhook-secret"
generate_key "$secret_dir/github-scm-ed25519"
generate_key "$secret_dir/slave-ed25519"
generate_key "$secret_dir/cloud-ed25519"
chmod 0600 "$secret_dir"/*
chmod 0644 "$secret_dir"/*.pub

printf '%s\n' 'Authorize this key for wage on Linux1:'
cat "$secret_dir/slave-ed25519.pub"
printf '%s\n' 'Authorize this key for the deployment account on the cloud node:'
cat "$secret_dir/cloud-ed25519.pub"
printf '%s\n' 'Add this dedicated Jenkins SCM authentication key to the GitHub account:'
cat "$secret_dir/github-scm-ed25519.pub"
printf '%s\n' \
  'Next: add verified slave-known-hosts, cloud-known-hosts, github-known-hosts and github-pat files under /etc/jenkins/secrets.'
