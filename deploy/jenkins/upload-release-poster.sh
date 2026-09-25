#!/bin/sh
set -eu

# 只在已通过生产门禁、且通知策略允许时由 notify-feishu.sh 调用。
: "${WAO_POSTER_OUTPUT:?WAO_POSTER_OUTPUT is required}"
: "${WAO_POSTER_TIME:?WAO_POSTER_TIME is required}"
: "${WAO_POSTER_BUILD:?WAO_POSTER_BUILD is required}"
: "${WAO_POSTER_SHA:?WAO_POSTER_SHA is required}"

if [ -z "${FEISHU_APP_ID:-}" ] || [ -z "${FEISHU_APP_SECRET:-}" ]; then
  echo 'Jenkins release image skipped: Feishu app credentials are unavailable.' >&2
  exit 1
fi

java -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
  -cp deploy/jenkins/lib/release-poster.jar ReleasePoster --render "$WAO_POSTER_OUTPUT" \
  wao-web/src/config/nav.ts "$WAO_POSTER_TIME" "$WAO_POSTER_BUILD" \
  "$WAO_POSTER_SHA" "${WAO_COMMIT_MESSAGE:-}"

request_file="$(mktemp)"
header_file="$(mktemp)"
cleanup() { rm -f -- "$request_file" "$header_file"; }
trap cleanup EXIT HUP INT TERM
chmod 600 "$request_file" "$header_file"
jq -n '{app_id:env.FEISHU_APP_ID,app_secret:env.FEISHU_APP_SECRET}' >"$request_file"

token_response="$(curl --fail --silent --show-error --max-time 10 \
  -X POST 'https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal' \
  -H 'Content-Type: application/json' --data-binary "@$request_file")"
token="$(printf '%s' "$token_response" \
  | jq -r 'if .code == 0 then .tenant_access_token // empty else empty end')"
if [ -z "$token" ]; then
  echo 'Jenkins release image skipped: Feishu token request failed.' >&2
  exit 1
fi
printf 'Authorization: Bearer %s\n' "$token" >"$header_file"
unset token token_response

image_response="$(curl --fail --silent --show-error --max-time 20 \
  -X POST 'https://open.feishu.cn/open-apis/im/v1/images' \
  -H "@$header_file" -F image_type=message \
  -F "image=@$WAO_POSTER_OUTPUT;type=image/png")"
image_key="$(printf '%s' "$image_response" \
  | jq -r 'if .code == 0 then .data.image_key // empty else empty end')"
if [ -z "$image_key" ]; then
  echo 'Jenkins release image skipped: Feishu image upload failed.' >&2
  exit 1
fi
printf '%s' "$image_key"
