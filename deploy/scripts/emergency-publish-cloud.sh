#!/bin/sh
# Runs on the cloud host from the manually dispatched GitHub Actions workflow.
set -eu

stage="${1:?Usage: emergency-publish-cloud.sh STAGE_DIR FULL_SHA}"
release="${2:?Full release SHA is required}"
case "$release" in *[!0-9a-f]*|'') exit 2 ;; esac
[ "${#release}" -eq 40 ] || exit 2
case "$stage" in /tmp/wao-emergency-*) ;; *) exit 2 ;; esac
stage_id="${stage#/tmp/wao-emergency-}"
case "$stage_id" in *[!0-9]*|'') exit 2 ;; esac
[ "$(id -u)" -eq 0 ] || { echo 'Cloud publishing requires root.' >&2; exit 1; }
test -f "$stage/wao-static.tgz"
test -f "$stage/emergency-publish-cloud.sh"
test -d "$stage/agent-dist"

ready() {
  host="$1"
  ip="$(sh /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh "$host")" || return 1
  response="$(curl -4 -kfsS --max-time 8 --resolve "$host:9000:$ip" \
    "https://$host:9000/api/edge/ready")" || return 1
  printf '%s' "$response" | grep -Eq '"code"[[:space:]]*:[[:space:]]*0' &&
    printf '%s' "$response" | grep -Eq '"protocol"[[:space:]]*:[[:space:]]*1'
}

ready linux1.wayuwa.cn || {
  echo 'Linux1 is not directly ready from the cloud; refusing to switch the public page.' >&2
  exit 1
}
# Stage and verify immutable assets before touching live routing or index.html.
python3 "$stage/static-release.py" install "$stage/wao-static.tgz" \
  /var/www/wao/releases "$release"
install -m 0644 "$stage/wao.wayuwa.cn.conf.template" \
  /opt/wao/deploy/nginx/wao.wayuwa.cn.conf.template
install -m 0755 "$stage/resolve-gateway-ipv4.sh" \
  /opt/wao/deploy/scripts/resolve-gateway-ipv4.sh
install -m 0755 "$stage/render-nginx-config.sh" \
  /opt/wao/deploy/scripts/render-nginx-config.sh
install -m 0755 "$stage/set-nginx-upstream-state.sh" \
  /opt/wao/deploy/scripts/set-nginx-upstream-state.sh

# Restore Linux1 first, then drain Linux2. The switch may briefly interrupt requests.
/opt/wao/deploy/scripts/set-nginx-upstream-state.sh slave up
/opt/wao/deploy/scripts/set-nginx-upstream-state.sh master down
ready linux1.wayuwa.cn || exit 1

python3 "$stage/publish-static-routing.py" /etc/wao-public-edge/routing.json "$release"
release_dir="/var/www/wao/releases/$release"
install -d /var/www/wao/dist /var/www/wao-agent/dist
find "$release_dir" -mindepth 1 -maxdepth 1 ! -name index.html \
  ! -name release-manifest.json -exec cp -a -- {} /var/www/wao/dist/ \;
find /var/www/wao-agent/dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
cp -a "$stage/agent-dist/." /var/www/wao-agent/dist/
install -m 0644 "$release_dir/index.html" /var/www/wao/dist/.index.html.next
mv -f /var/www/wao/dist/.index.html.next /var/www/wao/dist/index.html
nginx -t
systemctl reload nginx

short="$(printf '%.7s' "$release")"
attempt=1
while [ "$attempt" -le 12 ]; do
  if ready linux1.wayuwa.cn &&
    curl -fsS --max-time 8 https://wao.wayuwa.cn/api/edge/ready \
      | grep -Eq '"protocol"[[:space:]]*:[[:space:]]*1' &&
    curl -fsS --max-time 8 https://wao.wayuwa.cn/ \
      | grep -Fq "data-v=\"$short\""; then
    printf 'WAO_EMERGENCY_PUBLIC_RELEASE_VERIFIED=%s\n' "$release"
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 5
done
echo 'Public release did not pass final readiness and version checks.' >&2
exit 1
