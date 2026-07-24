#!/bin/sh
set -eu

MASTER_PUBLIC_HOST="${MASTER_PUBLIC_HOST:-linux2.wayuwa.cn}"
SLAVE_PUBLIC_HOST="${SLAVE_PUBLIC_HOST:-windows1.wayuwa.cn}"

check_gateway() {
  name="$1"
  host="$2"
  state_file="/tmp/wao_alert_${name}"

  if curl -kfsS --max-time 10 "https://${host}:9000/api/hello" | grep -q '"code":0'; then
    rm -f "${state_file}"
    return 0
  fi

  now="$(date +%s)"
  last=0
  if [ -f "${state_file}" ]; then
    last="$(stat -c %Y "${state_file}")"
  fi

  if [ $((now - last)) -gt 600 ]; then
    if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
      curl -fsS -X POST "${ALERT_WEBHOOK_URL}" \
        -H 'Content-Type: application/json' \
        -d "{\"msgtype\":\"text\",\"text\":{\"content\":\"[WAO] ${name} gateway health check failed\"}}" || true
    fi
    touch "${state_file}"
  fi
  return 1
}

failed=0
check_gateway master "${MASTER_PUBLIC_HOST}" || failed=1
check_gateway slave "${SLAVE_PUBLIC_HOST}" || failed=1
exit "${failed}"
