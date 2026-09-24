#!/bin/sh
set -eu

project_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT HUP INT TERM

mkdir -p "$fixture/master/deploy" "$fixture/run" "$fixture/bin"
printf '%s\n' 'FEISHU_COMMENT_WEBHOOK_URL=https://example.invalid/webhook' \
  >"$fixture/master/deploy/.env.secrets"

cat >"$fixture/bin/curl" <<'EOF'
#!/bin/sh
set -eu
if [ -n "${WAO_TEST_POLICY_RESPONSE:-}" ]; then
  printf '%s' "$WAO_TEST_POLICY_RESPONSE"
  exit 0
fi
is_policy=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    */api/public/notification-settings/jenkins-*)
      is_policy=true
      if [ -n "${WAO_TEST_EXPECT_POLICY_URL:-}" ] && [ "$1" != "$WAO_TEST_EXPECT_POLICY_URL" ]; then
        echo 'Unexpected notification policy URL' >&2
        exit 22
      fi
      ;;
  esac
  if [ "$1" = --data ]; then
    shift
    printf '%s' "$1" >"$WAO_TEST_PAYLOAD"
  fi
  shift
done
if [ "$is_policy" = true ]; then
  printf '%s' '{"code":0,"data":{"allowed":true}}'
  exit 0
fi
printf '%s' '{"code":0}'
EOF
chmod +x "$fixture/bin/curl"

assert_json() {
  filter="$1"
  jq -e "$filter" "$fixture/payload.json" >/dev/null || {
    jq . "$fixture/payload.json" >&2
    exit 1
  }
}

cat >"$fixture/run/wao-deploy-status.env" <<'EOF'
slave_status=success
slave_attempts=1
slave_detail=abc1234
master_status=success
master_attempts=1
master_detail=abc1234
cloud_status=success
cloud_detail=abc1234
verification_status=success
verification_detail=双节点、云端与公共入口检查通过
publish_completed_at=2026-09-24 16:39:00
EOF

PATH="$fixture/bin:$PATH" \
WAO_TEST_PAYLOAD="$fixture/payload.json" \
WAO_PIPELINE_RESULT=success \
WAO_TEST_EXPECT_POLICY_URL=https://wao.wayuwa.cn/api/public/notification-settings/jenkins-success \
WAO_MASTER_PROJECT_ROOT="$fixture/master" \
WAO_RUN_TEMP="$fixture/run" \
WAO_DEPLOY_REQUIRED=true \
DEPLOY_PRODUCTION=true \
WAO_GIT_SHA=abcdef1234567890abcdef1234567890abcdef12 \
WAO_BATCH_COMMIT_COUNT=2 \
WAO_COMMIT_MESSAGE='- feat(task): 精简 AI Run 通知
- fix(deploy): 精简 Jenkins 通知' \
BUILD_NUMBER=321 \
sh "$project_root/deploy/jenkins/notify-feishu.sh"

assert_json '.card.header.title.content == "WAO 生产发布 ✅ 成功"'
assert_json '.card.header.template == "green"'
assert_json '.card.elements[0].text.content | contains("双节点、云端与公共入口检查通过")'
assert_json '.card.elements[0].text.content | contains("精简 Jenkins 通知")'
assert_json '.card.elements[0].text.content | contains("**发布时间** 2026-09-24 16:39:00")'
rm -f "$fixture/payload.json"

cat >"$fixture/run/wao-deploy-status.env" <<'EOF'
slave_status=success
slave_attempts=1
slave_detail=abcdef12
master_status=failed
master_attempts=3
master_detail=健康检查未通过
cloud_status=not_started
cloud_detail=not_started
verification_status=not_started
verification_detail=not_started
EOF

PATH="$fixture/bin:$PATH" \
WAO_TEST_PAYLOAD="$fixture/payload.json" \
WAO_PIPELINE_RESULT=failure \
WAO_TEST_EXPECT_POLICY_URL=https://wao.wayuwa.cn/api/public/notification-settings/jenkins-failure \
WAO_MASTER_PROJECT_ROOT="$fixture/master" \
WAO_RUN_TEMP="$fixture/run" \
WAO_DEPLOY_REQUIRED=true \
DEPLOY_PRODUCTION=true \
WAO_FAILURE_STAGE='Deploy Linux slave, master and cloud' \
WAO_GIT_SHA=abcdef1234567890abcdef1234567890abcdef12 \
WAO_BATCH_COMMIT_COUNT=1 \
WAO_COMMIT_MESSAGE='- feat(task): 精简通知' \
BUILD_NUMBER=322 \
sh "$project_root/deploy/jenkins/notify-feishu.sh"

assert_json '.card.header.title.content == "WAO 生产发布 ❌ 失败"'
assert_json '.card.elements | length == 2'
assert_json '[.card.elements[].text.content? // ""] | join("\n") | contains("三节点滚动发布：健康检查未通过")'
assert_json '[.card.elements[].text.content? // ""] | join("\n") | contains("**本次改动**") | not'
assert_json '.card.elements[1].actions[0].text.content == "查看 Jenkins 详情"'
assert_json '.card.elements[1].actions[0].url == "https://wao.wayuwa.cn/jenkins/job/wao-main-cd/322/"'

cat >"$fixture/run/wao-deploy-status.env" <<'EOF'
slave_status=not_started
slave_attempts=0
slave_detail=not_started
master_status=not_started
master_attempts=0
master_detail=not_started
cloud_status=not_started
cloud_detail=not_started
verification_status=not_started
verification_detail=not_started
EOF

PATH="$fixture/bin:$PATH" \
WAO_TEST_PAYLOAD="$fixture/payload.json" \
WAO_PIPELINE_RESULT=failure \
WAO_MASTER_PROJECT_ROOT="$fixture/master" \
WAO_RUN_TEMP="$fixture/run" \
WAO_DEPLOY_REQUIRED=true \
DEPLOY_PRODUCTION=true \
WAO_FAILURE_STAGE='Backend tests' \
WAO_GIT_SHA=abcdef1234567890abcdef1234567890abcdef12 \
WAO_BATCH_COMMIT_COUNT=1 \
WAO_COMMIT_MESSAGE='- feat(task): 精简通知' \
BUILD_NUMBER=323 \
sh "$project_root/deploy/jenkins/notify-feishu.sh"

assert_json '[.card.elements[].text.content? // ""] | join("\n") | contains("**失败原因**\n后端测试")'

rm -f "$fixture/payload.json"
quiet_output="$(PATH="$fixture/bin:$PATH" \
  WAO_TEST_PAYLOAD="$fixture/payload.json" \
  WAO_TEST_POLICY_RESPONSE='{"code":0,"data":{"allowed":false,"reason":"当前处于沉默时段 22:00–08:00"}}' \
  WAO_PIPELINE_RESULT=failure \
  WAO_MASTER_PROJECT_ROOT="$fixture/master" \
  WAO_RUN_TEMP="$fixture/run" \
  WAO_FAILURE_STAGE='Backend tests' \
  sh "$project_root/deploy/jenkins/notify-feishu.sh")"
printf '%s' "$quiet_output" | grep -q 'Jenkins failure notification skipped: 当前处于沉默时段 22:00–08:00'
test ! -e "$fixture/payload.json"


# 成功时策略被拒绝、无法读取或生产检查未完成，均不能发绿色卡片。
for policy in '{"code":0,"data":{"allowed":false,"reason":"静默"}}' '{"code":0}'; do
  PATH="$fixture/bin:$PATH" WAO_TEST_PAYLOAD="$fixture/payload.json" \
    WAO_TEST_POLICY_RESPONSE="$policy" WAO_PIPELINE_RESULT=success \
    WAO_MASTER_PROJECT_ROOT="$fixture/master" WAO_RUN_TEMP="$fixture/run" \
    sh "$project_root/deploy/jenkins/notify-feishu.sh"
  test ! -e "$fixture/payload.json"
done
PATH="$fixture/bin:$PATH" WAO_TEST_PAYLOAD="$fixture/payload.json" \
  WAO_PIPELINE_RESULT=success WAO_MASTER_PROJECT_ROOT="$fixture/master" \
  WAO_RUN_TEMP="$fixture/run" WAO_DEPLOY_REQUIRED=true DEPLOY_PRODUCTION=true \
  sh "$project_root/deploy/jenkins/notify-feishu.sh"
test ! -e "$fixture/payload.json"
PATH="$fixture/bin:$PATH" WAO_TEST_PAYLOAD="$fixture/payload.json" \
  WAO_PIPELINE_RESULT=success WAO_MASTER_PROJECT_ROOT="$fixture/master" \
  WAO_RUN_TEMP="$fixture/run" WAO_DEPLOY_REQUIRED=false DEPLOY_PRODUCTION=true \
  sh "$project_root/deploy/jenkins/notify-feishu.sh"
assert_json '.card.header.title.content == "WAO 验证（无需部署） ✅ 成功"'
assert_json '.card.elements[0].text.content | contains("未发布到生产环境")'

echo 'Jenkins Feishu notification tests passed.'
