#!/bin/sh
# Fast release input checks. Full test suites run during development, outside CD.
set -eu

: "${WAO_GIT_SHA:?}"
: "${WAO_RUN_TEMP:?}"
printf '%s' "$WAO_GIT_SHA" | grep -Eq '^[0-9a-f]{40}$'
git diff --check "$WAO_CHANGE_BASE" "$WAO_GIT_SHA"

for migration_dir in \
  wao-server/src/main/resources/db/migration \
  agent-server/src/main/resources/db/migration; do
  duplicates="$(find "$migration_dir" -type f -name 'V*__*.sql' -print \
    | sed -E 's#^.*/V([^_]*)__.*$#\1#' | sort | uniq -d)"
  if [ -n "$duplicates" ]; then
    printf '数据库迁移版本重复：%s：%s\n' "$migration_dir" "$duplicates" >&2
    exit 1
  fi
done

for script in \
  deploy/jenkins/ci-build-frontends.sh \
  deploy/jenkins/ci-build-release.sh \
  deploy/jenkins/ci-frontend-build.sh \
  deploy/jenkins/cleanup-workspace.sh \
  deploy/jenkins/ci-package.sh \
  deploy/jenkins/ci-production-verify.sh; do
  sh -n "$script"
done
bash -n deploy/jenkins/ci-deploy.sh
echo '发布输入和脚本语法检查通过。'
