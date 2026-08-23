#!/bin/sh
set -eu

state="${1:?state is required}"
description="${2:?description is required}"
: "${GITHUB_PAT:?GITHUB_PAT is required}"
: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"

case "$state" in pending|success|failure|error) ;; *) exit 2 ;; esac

payload="$(jq -n \
  --arg state "$state" \
  --arg description "$description" \
  --arg target_url "${BUILD_URL:-http://127.0.0.1:8081/}" \
  '{state:$state,context:"jenkins/wao-main-cd",description:$description,target_url:$target_url}')"

curl --fail --silent --show-error \
  -X POST \
  -H 'Accept: application/vnd.github+json' \
  -H "Authorization: Bearer $GITHUB_PAT" \
  -H 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/wayuwacn/wao/statuses/$WAO_GIT_SHA" \
  --data "$payload" >/dev/null
