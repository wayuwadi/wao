#!/bin/sh
set -eu

secret_dir='/etc/jenkins/secrets'
admin_password="$(tr -d '\r\n' <"$secret_dir/admin-password")"

systemctl is-active --quiet jenkins.service
systemctl is-active --quiet wao-jenkins-webhook-tunnel.service
systemctl is-active --quiet wao-jenkins-backup.timer

listen="$(ss -H -lnt '( sport = :8081 )')"
printf '%s' "$listen" | grep -q '127.0.0.1:8081'
if printf '%s' "$listen" | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\[::\]):8081'; then
  echo 'Jenkins unexpectedly listens on a public address.' >&2
  exit 1
fi

curl --fail --silent --show-error -u "admin:$admin_password" \
  http://127.0.0.1:8081/api/json \
  | jq -e '.mode == "NORMAL" and .numExecutors == 1' >/dev/null

id -nG jenkins | tr ' ' '\n' | grep -qx docker
sudo -n -u jenkins docker info >/dev/null

if [ "${VERIFY_PUBLIC_WEBHOOK:-true}" = true ]; then
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    https://wao.wayuwa.cn/github-webhook/)" = 405
fi

echo 'WAO Jenkins controller, local-only UI and public webhook route are healthy.'
