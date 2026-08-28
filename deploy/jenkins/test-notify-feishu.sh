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
while [ "$#" -gt 0 ]; do
  if [ "$1" = --data ]; then
    shift
    printf '%s' "$1" >"$WAO_TEST_PAYLOAD"
  fi
  shift
done
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
windows_status=success
windows_attempts=1
windows_detail=abc1234
linux_status=success
linux_attempts=1
linux_detail=abc1234
cloud_status=success
cloud_detail=abc1234
verification_status=success
verification_detail=双节点、云端与公共入口检查通过
EOF

PATH="$fixture/bin:$PATH" \
WAO_TEST_PAYLOAD="$fixture/payload.json" \
WAO_PIPELINE_RESULT=success \
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

test ! -e "$fixture/payload.json"

cat >"$fixture/run/wao-deploy-status.env" <<'EOF'
windows_status=success
windows_attempts=1
windows_detail=abcdef12
linux_status=failed
linux_attempts=3
linux_detail=健康检查未通过
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
WAO_FAILURE_STAGE='Deploy Windows, Linux and cloud' \
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
windows_status=not_started
windows_attempts=0
windows_detail=not_started
linux_status=not_started
linux_attempts=0
linux_detail=not_started
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

echo 'Jenkins Feishu notification tests passed.'
