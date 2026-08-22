#!/bin/sh
set -eu

status_file="${WAO_RUN_TEMP:?WAO_RUN_TEMP is required}/wao-deploy-status.env"
mark_verification() {
  value="$1"
  if [ -f "$status_file" ]; then
    sed -i '/^verification_status=/d' "$status_file"
    printf 'verification_status=%s\n' "$value" >>"$status_file"
  fi
}
trap 'mark_verification failed' EXIT HUP INT TERM

hello="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/api/hello)"
printf '%s' "$hello" | grep -q '"code":0'
printf '%s' "$hello" | grep -q '"service":"wao-server"'

agent_health="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/api/agent/health)"
printf '%s' "$agent_health" | grep -q '"status":"UP"'
curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/agent/ | grep -q 'id="app"'
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/game/)" = 308
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/insight/)" = 410

header_value() {
  path="$1"
  header="$2"
  curl --silent --show-error --head "https://wao.wayuwa.cn$path" \
    | tr -d '\r' | awk -F ': ' -v expected="$header" \
      'tolower($1) == tolower(expected) {print $2}'
}

test "$(header_value /api/ai/workflows X-WAO-API-Route)" = dual-gateway-ai
test "$(header_value /api/ai/goals X-WAO-API-Route)" = dual-gateway-ai
test "$(header_value /api/settings/ai-accounts X-WAO-API-Route)" = linux-vpn-master
test "$(header_value /api/ai/runner/accounts X-WAO-API-Route)" = linux-vpn-master
test "$(header_value /api/ai/runner/assistant-v2/claim X-WAO-API-Route)" = linux-vpn-master
test "$(header_value /api/tianren/readings/today/interpret/stream X-WAO-API-Route)" = linux-vpn-master

probe=1
while [ "$probe" -le 8 ]; do
  feishu="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
    "https://wao.wayuwa.cn/api/auth/feishu/authorize?scene=authorization&probe=$probe")"
  printf '%s' "$feishu" | grep -q '"code":0'
  printf '%s' "$feishu" | grep -q 'https://open.feishu.cn/open-apis/authen/v1/authorize'
  probe=$((probe + 1))
done

# Codex 出口不阻断业务发布，只留下明确告警。
if ! sh "$WAO_MASTER_PROJECT_ROOT/deploy/scripts/probe-codex-egress.sh"; then
  echo 'WARNING: Linux 主节点 Codex 出站探针失败；业务发布仍视为成功。' >&2
fi

mark_verification success
trap - EXIT HUP INT TERM
exit 0
