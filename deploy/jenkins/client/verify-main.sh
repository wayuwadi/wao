#!/usr/bin/env bash
set -euo pipefail
# Jenkins 的分支环境变量格式随 SCM 调用而变；校验真正参与构建的 Git 对象。
head_sha="$(git rev-parse --verify 'HEAD^{commit}')"
main_sha="$(git rev-parse --verify 'refs/remotes/origin/main^{commit}')"
if [[ "$head_sha" != "$main_sha" ]]; then
  echo '正式发布只允许本次检出的 origin/main 提交。' >&2
  exit 1
fi
