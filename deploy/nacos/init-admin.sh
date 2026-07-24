#!/bin/sh
set -eu

: "${NACOS_PASSWORD:?NACOS_PASSWORD is required}"
nacos_url="${NACOS_URL:-http://nacos:8848}"

login() {
  curl -fsS -o /dev/null \
    -X POST "${nacos_url}/nacos/v3/auth/user/login" \
    --data-urlencode 'username=nacos' \
    --data-urlencode "password=${NACOS_PASSWORD}"
}

attempt=0
until curl -fsS -o /dev/null "${nacos_url}/nacos/v1/console/health/readiness"; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 60 ]; then
    echo 'Nacos did not become ready in time.' >&2
    exit 1
  fi
  sleep 2
done

if ! login; then
  curl -fsS -o /dev/null \
    -X POST "${nacos_url}/nacos/v3/auth/user/admin" \
    --data-urlencode "password=${NACOS_PASSWORD}"
fi

login
echo 'Nacos administrator is initialized and verified.'
