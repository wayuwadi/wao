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
response_file="$(mktemp)"
cleanup() { rm -f -- "$request_file" "$header_file" "$response_file"; }
trap cleanup EXIT HUP INT TERM
chmod 600 "$request_file" "$header_file" "$response_file"
jq -n '{app_id:env.FEISHU_APP_ID,app_secret:env.FEISHU_APP_SECRET}' >"$request_file"

token_http="$(curl --silent --show-error --max-time 10 \
  --output "$response_file" --write-out '%{http_code}' \
  -X POST 'https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal' \
  -H 'Content-Type: application/json' --data-binary "@$request_file")" || {
  echo 'Jenkins release image skipped: Feishu token transport failed.' >&2
  exit 1
}
token_code="$(jq -r '.code // empty' "$response_file" 2>/dev/null || true)"
token="$(jq -r 'if .code == 0 then .tenant_access_token // empty else empty end' \
  "$response_file" 2>/dev/null || true)"
if [ -z "$token" ]; then
  echo "Jenkins release image skipped: Feishu token request failed (HTTP $token_http, code ${token_code:-unknown})." >&2
  exit 1
fi
printf 'Authorization: Bearer %s\n' "$token" >"$header_file"
unset token
: >"$response_file"

image_http="$(curl --silent --show-error --max-time 20 \
  --output "$response_file" --write-out '%{http_code}' \
  -X POST 'https://open.feishu.cn/open-apis/im/v1/images' \
  -H "@$header_file" -F image_type=message \
  -F "image=@$WAO_POSTER_OUTPUT;type=image/png")" || {
  echo 'Jenkins release image skipped: Feishu image upload transport failed.' >&2
  exit 1
}
image_code="$(jq -r '.code // empty' "$response_file" 2>/dev/null || true)"
image_key="$(jq -r 'if .code == 0 then .data.image_key // empty else empty end' \
  "$response_file" 2>/dev/null || true)"
if [ -z "$image_key" ]; then
  echo "Jenkins release image skipped: Feishu image upload failed (HTTP $image_http, code ${image_code:-unknown})." >&2
  if [ "$image_code" = 99991672 ]; then
    echo 'Feishu app lacks image upload permission: grant im:resource:upload or im:resource, then publish the app version.' >&2
  fi
  exit 1
fi
printf '%s' "$image_key"
