#!/bin/sh
set -eu

state="${1:?state is required}"
description="${2:?description is required}"
: "${GITHUB_PAT:?GITHUB_PAT is required}"
: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"

case "$state" in pending|success|failure|error) ;; *) exit 2 ;; esac

if [ "$state" = failure ] && [ -n "${WAO_RUN_TEMP:-}" ]; then
  status_file="$WAO_RUN_TEMP/wao-deploy-status.env"
  if [ -f "$status_file" ]; then
    for prefix in linux2 cloud verification; do
      status="$(sed -n "s/^${prefix}_status=//p" "$status_file" | tail -n 1)"
      if [ "$status" = failed ]; then
        detail="$(sed -n "s/^${prefix}_detail=//p" "$status_file" | tail -n 1)"
        if [ -n "$detail" ] && [ "$detail" != not_started ]; then
          description="$description：$detail"
        fi
        break
      fi
    done
  fi
fi
description="$(printf '%s' "$description" | tr '\r\n' '  ' | cut -c1-140)"

payload="$(jq -n \
  --arg state "$state" \
  --arg description "$description" \
  --arg target_url "https://wao.wayuwa.cn/jenkins/job/wao-main-cd/${BUILD_NUMBER:-}/" \
  '{state:$state,context:"jenkins/wao-main-cd",description:$description,target_url:$target_url}')"

post_status() {
  sha="$1"
  printf '%s' "$sha" | grep -Eq '^[0-9a-f]{40}$' || {
    echo "Invalid status SHA: $sha" >&2
    exit 2
  }
  if ! curl --fail --silent --show-error \
      --connect-timeout 10 --max-time 30 \
      --retry 5 --retry-delay 2 --retry-all-errors \
      -X POST \
      -H 'Accept: application/vnd.github+json' \
      -H "Authorization: Bearer $GITHUB_PAT" \
      -H 'X-GitHub-Api-Version: 2022-11-28' \
      "https://api.github.com/repos/wayuwacn/wao/statuses/$sha" \
      --data "$payload" >/dev/null; then
    # Commit status is observability metadata. A transient GitHub/TLS outage after a
    # verified production rollout must not relabel that rollout as failed.
    echo "WARNING: GitHub commit status delivery failed for $sha; Jenkins result is unchanged." >&2
  fi
}

if [ -n "${WAO_STATUS_SHA_FILE:-}" ] && [ -s "$WAO_STATUS_SHA_FILE" ]; then
  while IFS= read -r sha; do
    [ -n "$sha" ] && post_status "$sha"
  done <"$WAO_STATUS_SHA_FILE"
else
  post_status "$WAO_GIT_SHA"
fi
