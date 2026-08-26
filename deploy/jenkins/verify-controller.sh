#!/bin/sh
set -eu

secret_dir='/etc/jenkins/secrets'

systemctl is-active --quiet jenkins.service
systemctl is-active --quiet wao-jenkins-webhook-tunnel.service
systemctl is-active --quiet wao-jenkins-backup.timer

listen="$(ss -H -lnt '( sport = :8081 )')"
printf '%s' "$listen" | grep -q '127.0.0.1:8081'
if printf '%s' "$listen" | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\[::\]):8081'; then
  echo 'Jenkins unexpectedly listens on a public address.' >&2
  exit 1
fi

curl --fail --silent --show-error \
  -H 'X-Forwarded-User: wao-controller-verification' \
  -H 'X-Forwarded-Groups: wao-admins' \
  http://127.0.0.1:8081/jenkins/api/json \
  | jq -e '.mode == "NORMAL" and .numExecutors == 1' >/dev/null

id -nG jenkins | tr ' ' '\n' | grep -qx docker
sudo -n -u jenkins docker info >/dev/null

if [ "${VERIFY_PUBLIC_WEBHOOK:-true}" = true ]; then
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    https://wao.wayuwa.cn/github-webhook/)" = 405
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    https://wao.wayuwa.cn/jenkins/)" = 401
fi

echo 'WAO Jenkins controller, WAO-protected UI and public webhook route are healthy.'
