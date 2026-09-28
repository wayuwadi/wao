#!/usr/bin/env bash
# Installed as a Certbot deploy hook on Linux2. No keys enter the repository.
set -euo pipefail
if [[ "${RENEWED_LINEAGE:-}" == /etc/letsencrypt/live/wao-linux2-public ]]; then
  exec /etc/wao-node-tls/deploy-local-node-certificate.sh
fi
[[ "${RENEWED_LINEAGE:-}" == /etc/letsencrypt/live/wao-linux1-public ]] || exit 0
if grep -qs '^WAO_DEPLOY_TOPOLOGY=linux2-only$' /home/wadi/桌面/deploy/wao/deploy/.env; then
  exit 0
fi
release="$(date -u +%Y%m%dT%H%M%SZ)-$$"
peer=wage@linux1.wayuwa.cn
root=/home/wage/.config/wao-public-tls
ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10
  -o ServerAliveInterval=10 -o ServerAliveCountMax=3
  -i /etc/wao-node-tls/ssh-key -o UserKnownHostsFile=/etc/wao-node-tls/known-hosts)
retry() {
  for attempt in 1 2 3; do
    if "$@"; then return 0; fi
    [[ "$attempt" == 3 ]] || sleep 3
  done
  return 1
}
retry ssh -n "${ssh_args[@]}" "$peer" "umask 077; mkdir -p '$root/releases/$release'"
send_files() {
  tar -h -C "$RENEWED_LINEAGE" -cf - fullchain.pem privkey.pem \
    | ssh "${ssh_args[@]}" "$peer" "umask 077; tar -C '$root/releases/$release' -xf -"
}
retry send_files
remote_script="$(mktemp)"
trap 'rm -f "$remote_script"' EXIT
cat >"$remote_script" <<'REMOTE'
set -euo pipefail
umask 077
root=/home/wage/.config/wao-public-tls
release="$1"
[[ "$release" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9]+$ ]] || exit 2
cert="$root/releases/$release/fullchain.pem"
key="$root/releases/$release/privkey.pem"
openssl x509 -in "$cert" -noout -checkhost linux1.wayuwa.cn
openssl x509 -in "$cert" -noout -checkend 604800
cert_public=$(openssl x509 -in "$cert" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum)
key_public=$(openssl pkey -in "$key" -pubout -outform DER | sha256sum)
[[ "$cert_public" == "$key_public" ]] || { echo 'Certificate key mismatch.' >&2; exit 1; }
old=$(readlink "$root/current")
ln -s "releases/$release" "$root/current.next.$$"
mv -Tf "$root/current.next.$$" "$root/current"
if ! docker exec wao-public-edge nginx -t || ! docker exec wao-public-edge nginx -s reload || \
   ! curl --noproxy '*' --fail --silent --show-error --resolve linux1.wayuwa.cn:9443:127.0.0.1 \
     --max-time 10 https://linux1.wayuwa.cn:9443/_edge/health >/dev/null; then
  ln -s "$old" "$root/current.rollback.$$"
  mv -Tf "$root/current.rollback.$$" "$root/current"
  docker exec wao-public-edge nginx -s reload || true
  exit 1
fi
echo 'Linux1 public certificate deployed and local HTTPS verified.'
REMOTE
activate() { ssh "${ssh_args[@]}" "$peer" "bash -s -- '$release'" <"$remote_script"; }
retry activate
