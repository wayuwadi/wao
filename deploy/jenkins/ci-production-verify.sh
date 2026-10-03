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

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
short_sha="$(printf '%.7s' "$WAO_GIT_SHA")"

# 完整下载后再检查正文，避免 grep 提前关闭管道让 curl 报写入失败并反复重试。
require_public_content() {
  asset_url="$1"
  expected_content="$2"
  asset_file="$WAO_RUN_TEMP/public-asset-probe.txt"
  curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
    --output "$asset_file" "$asset_url" || return 1
  if ! grep -Fq -- "$expected_content" "$asset_file"; then
    printf 'Public asset content did not match: %s\n' "$asset_url" >&2
    return 1
  fi
}

hello="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/api/hello)"
printf '%s' "$hello" | grep -q '"code":0'
printf '%s' "$hello" | grep -q '"service":"wao-server"'

notification_policy="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/api/public/notification-settings/jenkins-failure)"
printf '%s' "$notification_policy" \
  | jq -e '.code == 0 and (.data.allowed | type == "boolean") and (.data.reason | type == "string")' >/dev/null

test "$(curl -sS -o /dev/null -w '%{http_code}' https://wao.wayuwa.cn/agent/)" = 410
amer_demo="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/amer_demo_1/)"
printf '%s' "$amer_demo" | grep -q '<title>IT 安全风险管理 Demo</title>'
require_public_content https://wao.wayuwa.cn/amer_demo_1/styles.css '--navy:'
require_public_content https://wao.wayuwa.cn/amer_demo_1/app.js 'IT 安全风险管理'
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_1)" = 308
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_1/not-found.js)" = 404
amer_demo_v2="$(curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
  https://wao.wayuwa.cn/amer_demo_2/)"
printf '%s' "$amer_demo_v2" | grep -q '<title>IT Risk Management · Power Apps Demo</title>'
require_public_content https://wao.wayuwa.cn/amer_demo_2/styles.css '--power:'
require_public_content https://wao.wayuwa.cn/amer_demo_2/app.js 'amer-risk-demo-v2'
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_2)" = 308
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_2/not-found.js)" = 404
require_public_content https://wao.wayuwa.cn/amer_demo_6/ '<title>IT Risk Management v6 · Power Apps Demo</title>'
require_public_content https://wao.wayuwa.cn/amer_demo_6/styles.css '--power:'
require_public_content https://wao.wayuwa.cn/amer_demo_6/app.js 'IT Risk Management v6'
require_public_content https://wao.wayuwa.cn/amer_demo_6/enhancements.css '.risk-register-table'
require_public_content https://wao.wayuwa.cn/amer_demo_6/enhancements.js 'derivedRiskStatus'
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_6)" = 308
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_6/not-found.js)" = 404
require_public_content https://wao.wayuwa.cn/amer_demo_7/ '<title>IT Risk Management v7 · Power Apps Demo</title>'
require_public_content https://wao.wayuwa.cn/amer_demo_7/styles.css '--power:'
require_public_content https://wao.wayuwa.cn/amer_demo_7/app.js 'IT Risk Management v7'
require_public_content https://wao.wayuwa.cn/amer_demo_7/enhancements.css '.risk-register-table'
require_public_content https://wao.wayuwa.cn/amer_demo_7/enhancements.js 'IT Risk Management v7'
require_public_content https://wao.wayuwa.cn/amer_demo_7/enhancements.js "navButton('actions', '✓', 'My Actions')"
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_7)" = 308
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/amer_demo_7/not-found.js)" = 404
# V4 同时服务新入口和原 V3 地址；存储键继续沿用 V3，保留用户的演示数据。
for demo_path in amer_demo_3 amer_demo_4; do
  require_public_content "https://wao.wayuwa.cn/$demo_path/" '<title>IT Risk Management v4 · Power Apps Demo</title>'
  require_public_content "https://wao.wayuwa.cn/$demo_path/styles.css" '.score-journey'
  require_public_content "https://wao.wayuwa.cn/$demo_path/app.js" 'amer-risk-demo-v3'
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' "https://wao.wayuwa.cn/$demo_path")" = 308
  test "$(curl --silent --output /dev/null --write-out '%{http_code}' "https://wao.wayuwa.cn/$demo_path/not-found.js")" = 404
done
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/insight/)" = 410
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/jenkins)" = 308
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/jenkins/)" = 401
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/api/auth/jumpserver)" = 410
test "$(curl --silent --output /dev/null --write-out '%{http_code}' https://wao.wayuwa.cn/weknora/)" = 410

test "$(curl --silent --output /dev/null --write-out '%{http_code}' -X POST https://wao.wayuwa.cn/api/ai/runner/heartbeat)" = 410

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
test "$(header_value /api/tianren/readings/today/interpret/stream X-WAO-API-Route)" = linux-vpn-master
test "$(header_value /pluginWebUpdateNotice/web_version_by_plugin.json Cache-Control)" = \
  'no-cache, no-store, must-revalidate'

verify_frontend_version() {
  cache_buster="$(date +%s)"
  web_version="$(curl --fail --silent --show-error --retry 2 --retry-delay 2 --retry-all-errors \
    -H 'Cache-Control: no-cache' \
    "https://wao.wayuwa.cn/pluginWebUpdateNotice/web_version_by_plugin.json?t=${cache_buster}")" \
    || return 1
  index_html="$(curl --fail --silent --show-error --retry 2 --retry-delay 2 --retry-all-errors \
    -H 'Cache-Control: no-cache' \
    "https://wao.wayuwa.cn/index.html?t=${cache_buster}")" \
    || return 1

  printf '%s' "$web_version" | grep -Eq \
    "\"version\"[[:space:]]*:[[:space:]]*\"${short_sha}\"" \
    && printf '%s' "$index_html" | grep -q "data-v=\"${short_sha}\""
}

build_web="$(python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["buildWeb"]).lower())' "$WAO_RUN_TEMP/game-plan.json")"
if [ "$build_web" = true ]; then
frontend_attempt=1
while ! verify_frontend_version; do
  if [ "$frontend_attempt" -ge 12 ]; then
    echo "Production frontend did not converge to version ${short_sha} after 12 attempts." >&2
    exit 1
  fi
  echo "Production frontend is not yet at version ${short_sha}; retrying (${frontend_attempt}/12)." >&2
  frontend_attempt=$((frontend_attempt + 1))
  sleep 5
done

fi

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
