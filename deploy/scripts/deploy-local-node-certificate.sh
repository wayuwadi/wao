#!/usr/bin/env bash
# Linux2 Certbot deploy hook. All private files remain on Linux2.
set -euo pipefail
[[ "${RENEWED_LINEAGE:-}" == /etc/letsencrypt/live/wao-linux2-public ]] || exit 2
umask 077
root=/etc/wao-public-tls-linux2
release="$(date -u +%Y%m%dT%H%M%SZ)-$$"
install -d -m 0700 "$root/releases/$release"
install -m 0600 "$RENEWED_LINEAGE/fullchain.pem" "$root/releases/$release/fullchain.pem"
install -m 0600 "$RENEWED_LINEAGE/privkey.pem" "$root/releases/$release/privkey.pem"
cert="$root/releases/$release/fullchain.pem"
key="$root/releases/$release/privkey.pem"
openssl x509 -in "$cert" -noout -checkhost linux2.wayuwa.cn
openssl x509 -in "$cert" -noout -checkend 604800
cert_public=$(openssl x509 -in "$cert" -pubkey -noout | openssl pkey -pubin -outform DER | sha256sum)
key_public=$(openssl pkey -in "$key" -pubout -outform DER | sha256sum)
[[ "$cert_public" == "$key_public" ]] || { echo 'Certificate key mismatch.' >&2; exit 1; }
old=$(readlink "$root/current")
ln -s "releases/$release" "$root/current.next.$$"
mv -Tf "$root/current.next.$$" "$root/current"
if ! docker exec wao-public-edge nginx -t || ! docker exec wao-public-edge nginx -s reload || \
   ! curl --noproxy '*' --fail --silent --show-error --resolve linux2.wayuwa.cn:9443:127.0.0.1 \
     --retry 2 --retry-delay 1 --max-time 10 https://linux2.wayuwa.cn:9443/_edge/health >/dev/null; then
  ln -s "$old" "$root/current.rollback.$$"
  mv -Tf "$root/current.rollback.$$" "$root/current"
  docker exec wao-public-edge nginx -s reload || true
  exit 1
fi
echo 'Linux2 public certificate deployed and local HTTPS verified.'
