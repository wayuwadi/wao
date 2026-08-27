#!/bin/sh
set -eu

head="${1:-HEAD}"
fallback="${GIT_PREVIOUS_SUCCESSFUL_COMMIT:-${GIT_PREVIOUS_COMMIT:-${head}^}}"
version="${WAO_DEPLOYED_VERSION:-}"

if [ -z "$version" ]; then
  version="$(curl --fail --silent --show-error --max-time 10 \
    'https://wao.wayuwa.cn/pluginWebUpdateNotice/web_version_by_plugin.json' \
    | jq -r '.version // empty' 2>/dev/null || true)"
fi

case "$version" in
  [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]|\
  [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*)
    deployed="$(git rev-list --first-parent "$head" | awk -v prefix="$version" 'index($0, prefix) == 1 {print; exit}')"
    if [ -n "$deployed" ]; then
      printf '%s\n' "$deployed"
      exit 0
    fi
    ;;
esac

if ! git rev-parse --verify "${fallback}^{commit}" >/dev/null 2>&1; then
  fallback="$(git rev-list --max-count=1 --skip=1 --first-parent "$head")"
fi
test -n "$fallback"
git rev-parse "${fallback}^{commit}"
