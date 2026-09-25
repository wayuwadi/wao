#!/bin/sh
set -eu

project_root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT HUP INT TERM

mkdir -p "$fixture/master/deploy" "$fixture/run" "$fixture/bin"
printf '%s\n' 'FEISHU_COMMENT_WEBHOOK_URL=https://example.invalid/webhook' \
  'FEISHU_APP_ID=cli_test_app' \
  'FEISHU_APP_SECRET=cli_test_secret' \
  >"$fixture/master/deploy/.env.secrets"

cat >"$fixture/bin/curl" <<'EOF'
#!/bin/sh
set -eu
if [ -n "${WAO_TEST_POLICY_RESPONSE:-}" ]; then
  printf '%s' "$WAO_TEST_POLICY_RESPONSE"
  exit 0
fi
is_policy=false
is_token=false
is_image=false
output_file=''
write_out=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    */api/public/notification-settings/jenkins-*)
      is_policy=true
      if [ -n "${WAO_TEST_EXPECT_POLICY_URL:-}" ] && [ "$1" != "$WAO_TEST_EXPECT_POLICY_URL" ]; then
        echo 'Unexpected notification policy URL' >&2
        exit 22
      fi
      ;;
    */auth/v3/tenant_access_token/internal) is_token=true ;;
    */im/v1/images) is_image=true ;;
    --output|--write-out)
      option="$1"
      shift
      if [ "$option" = --output ]; then output_file="$1"; else write_out="$1"; fi
      ;;
    image=@*)
      image_path="${1#image=@}"
      image_path="${image_path%%;*}"
      test -s "$image_path"
      test "$(od -An -tx1 -N8 "$image_path" | tr -d ' \n')" = 89504e470d0a1a0a
      : >"$WAO_TEST_IMAGE_UPLOADED"
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
if [ "$is_token" = true ]; then
  response='{"code":0,"tenant_access_token":"test-token"}'
  status=200
  if [ "${WAO_TEST_TOKEN_FAIL:-false}" = true ]; then
    response='{"code":10003,"msg":"invalid credentials"}'
    status=400
  fi
  if [ -n "$output_file" ]; then printf '%s' "$response" >"$output_file"; else printf '%s' "$response"; fi
  if [ -n "$write_out" ]; then printf '%s' "$status"; fi
  exit 0
fi
if [ "$is_image" = true ]; then
  status=200
  if [ "${WAO_TEST_IMAGE_UPLOAD_FAIL:-false}" = true ]; then
    response='{"code":99991672,"msg":"Access denied. One of the following scopes is required: [im:resource:upload, im:resource]"}'
    status=400
  else
    response='{"code":0,"data":{"image_key":"img_test_release"}}'
  fi
  if [ -n "$output_file" ]; then printf '%s' "$response" >"$output_file"; else printf '%s' "$response"; fi
  if [ -n "$write_out" ]; then printf '%s' "$status"; fi
  exit 0
fi
if [ "${WAO_TEST_WEBHOOK_REJECT_IMAGE:-false}" = true ] \
    && jq -e '[.card.elements[] | select(.tag == "img")] | length > 0' \
      "$WAO_TEST_PAYLOAD" >/dev/null; then
  printf '%s' '{"code":19024,"msg":"card image rejected"}'
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
WAO_TEST_IMAGE_UPLOADED="$fixture/uploaded" \
WAO_PIPELINE_RESULT=success \
WAO_TEST_EXPECT_POLICY_URL=https://wao.wayuwa.cn/api/public/notification-settings/jenkins-success \
WAO_MASTER_PROJECT_ROOT="$fixture/master" \
WAO_RUN_TEMP="$fixture/run" \
WAO_DEPLOY_REQUIRED=true \
DEPLOY_PRODUCTION=true \
WAO_GIT_SHA=abcdef1234567890abcdef1234567890abcdef12 \
WAO_BATCH_COMMIT_COUNT=2 \
WAO_COMMIT_MESSAGE='- feat(reference-images): 建立截图即收的个人参考图库与带图 AI 提问
- fix(deploy): 精简 Jenkins 通知' \
BUILD_NUMBER=321 \
sh "$project_root/deploy/jenkins/notify-feishu.sh"

assert_json '.card.header.title.content == "WAO 生产发布 ✅ 成功"'
assert_json '.card.header.template == "green"'
assert_json '.card.elements[0].text.content | contains("双节点、云端与公共入口检查通过")'
assert_json '.card.elements[0].text.content | contains("精简 Jenkins 通知")'
assert_json '.card.elements[0].text.content | contains("**发布时间** 2026-09-24 16:39:00")'
assert_json '.card.elements[1].tag == "img" and .card.elements[1].img_key == "img_test_release"'
assert_json '.card.elements[2].actions[0].text.content == "打开参考图库"'
assert_json '.card.elements[2].actions[0].url == "https://wao.wayuwa.cn/reference-images"'
assert_json '.card.elements[2].actions[1].url == "https://wao.wayuwa.cn/jenkins/job/wao-main-cd/321/"'
test -e "$fixture/uploaded"
test -s "$fixture/run/wao-release-poster.png"
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
assert_json '.card.elements | length == 2'

# 图片上传失败仍只发送一张原有成功卡；不会把已验收的生产发布改判失败。
cat >"$fixture/run/wao-deploy-status.env" <<'EOF'
slave_status=success
master_status=success
cloud_status=success
verification_status=success
EOF
PATH="$fixture/bin:$PATH" WAO_TEST_PAYLOAD="$fixture/payload.json" \
  WAO_TEST_IMAGE_UPLOADED="$fixture/uploaded" WAO_TEST_IMAGE_UPLOAD_FAIL=true \
  WAO_PIPELINE_RESULT=success WAO_MASTER_PROJECT_ROOT="$fixture/master" \
  WAO_RUN_TEMP="$fixture/run" WAO_DEPLOY_REQUIRED=true DEPLOY_PRODUCTION=true \
  WAO_GIT_SHA=abcdef1234567890 WAO_COMMIT_MESSAGE='- fix(deploy): 修复发布结果判定' \
  BUILD_NUMBER=324 sh "$project_root/deploy/jenkins/notify-feishu.sh" \
  2>"$fixture/image-error.log"
assert_json '.card.header.title.content == "WAO 生产发布 ✅ 成功"'
assert_json '.card.elements | length == 2'
assert_json '.card.elements[1].actions | length == 1'
grep -Fq 'HTTP 400, code 99991672' "$fixture/image-error.log"
grep -Fq 'im:resource:upload or im:resource' "$fixture/image-error.log"
! grep -Fq 'test-token' "$fixture/image-error.log"

# 令牌接口返回 HTTP 400 时同样保留原通知，并只输出非敏感错误码。
PATH="$fixture/bin:$PATH" WAO_TEST_PAYLOAD="$fixture/payload.json" \
  WAO_TEST_TOKEN_FAIL=true WAO_PIPELINE_RESULT=success \
  WAO_MASTER_PROJECT_ROOT="$fixture/master" WAO_RUN_TEMP="$fixture/run" \
  WAO_DEPLOY_REQUIRED=true DEPLOY_PRODUCTION=true \
  WAO_GIT_SHA=abcdef1234567890 WAO_COMMIT_MESSAGE='- fix(deploy): 修复发布结果判定' \
  BUILD_NUMBER=326 sh "$project_root/deploy/jenkins/notify-feishu.sh" \
  2>"$fixture/token-error.log"
assert_json '.card.elements | length == 2'
grep -Fq 'Feishu token request failed (HTTP 400, code 10003)' "$fixture/token-error.log"
! grep -Fq 'cli_test_secret' "$fixture/token-error.log"

# 飞书明确拒绝带图卡时，重试一次原成功卡；网络未知状态不在这里模拟。
PATH="$fixture/bin:$PATH" WAO_TEST_PAYLOAD="$fixture/payload.json" \
  WAO_TEST_IMAGE_UPLOADED="$fixture/uploaded" WAO_TEST_WEBHOOK_REJECT_IMAGE=true \
  WAO_PIPELINE_RESULT=success WAO_MASTER_PROJECT_ROOT="$fixture/master" \
  WAO_RUN_TEMP="$fixture/run" WAO_DEPLOY_REQUIRED=true DEPLOY_PRODUCTION=true \
  WAO_GIT_SHA=abcdef1234567890 WAO_COMMIT_MESSAGE='- feat(reference-images): 收下参考截图' \
  BUILD_NUMBER=325 sh "$project_root/deploy/jenkins/notify-feishu.sh"
assert_json '.card.elements | length == 2'
assert_json '.card.elements[1].actions[0].url == "https://wao.wayuwa.cn/reference-images"'

poster_description="$(java -Dfile.encoding=UTF-8 \
  -cp "$project_root/deploy/jenkins/lib/release-poster.jar" ReleasePoster --describe \
  "$project_root/wao-web/src/config/nav.ts" \
  '- feat(reference-images): 建立截图即收的个人参考图库与带图 AI 提问')"
printf '%s' "$poster_description" | grep -Fq 'https://wao.wayuwa.cn/reference-images'
printf '%s' "$poster_description" | grep -Fq '建立截图即收的个人参考图库与带图 AI 提问'

echo 'Jenkins Feishu notification tests passed.'
