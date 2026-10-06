#!/bin/sh
set -eu

cache="${JENKINS_HOME:-$HOME}/.npm"
node_image='node:24.15.0-bookworm-slim'
mkdir -p "$cache"

project="${1:?frontend project is required}"
run_tests="${2:?run-tests flag is required}"
build_project="${3:?build flag is required}"
case "$project" in wao-web|agent-web) ;; *) echo "Invalid frontend project: $project" >&2; exit 2 ;; esac
case "$run_tests" in true|false) ;; *) echo "Invalid run-tests flag: $run_tests" >&2; exit 2 ;; esac
case "$build_project" in true|false) ;; *) echo "Invalid build flag: $build_project" >&2; exit 2 ;; esac

if [ "$run_tests" != true ] && [ "$build_project" != true ]; then
  echo "$project skipped: no tests or production build are required."
  exit 0
fi

run_npm() {
  project="$1"
  shift
  # slim Node 容器没有 Git；在宿主可信 checkout 导出完整记录给构建读取。
  if [ "$project" = wao-web ] && [ "${1:-}" = run ]; then
    [ "$(git rev-parse --is-shallow-repository)" = false ] || { echo 'Git history is shallow' >&2; exit 1; }
    [ "$(git rev-parse HEAD)" = "$WAO_GIT_SHA" ] || { echo 'Build SHA mismatch' >&2; exit 1; }
    mkdir -p wao-web/node_modules/.cache
    git log --topo-order --format='%H%x09%cI%x09%s' "$WAO_GIT_SHA" >wao-web/node_modules/.cache/wao-git-history.tsv
    # 使用发布协调者给出的实际上线边界，并导出可信首父链；容器不猜 HEAD^。
    range_file=wao-web/node_modules/.cache/wao-git-release-range.tsv
    rm -f "$range_file"
    if [ -n "${WAO_CHANGE_BASE:-}" ] && git cat-file -e "${WAO_CHANGE_BASE}^{commit}" 2>/dev/null \
        && git merge-base --is-ancestor "$WAO_CHANGE_BASE" "$WAO_GIT_SHA" \
        && git rev-list --first-parent "$WAO_GIT_SHA" | grep -qx "$WAO_CHANGE_BASE"; then
      git log --first-parent --format='%H%x09%P' "$WAO_CHANGE_BASE..$WAO_GIT_SHA" >"$range_file"
      git show -s --format='%H%x09%P' "$WAO_CHANGE_BASE" >>"$range_file"
    else
      echo 'Release boundary is unverified; update guide will only describe HEAD.' >&2
    fi
  fi
  docker run --rm \
    --cpus=2 --memory=4g --memory-swap=4g --pids-limit=512 \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e npm_config_cache=/tmp/npm-cache \
    -e WAO_GIT_SHA="${WAO_GIT_SHA:-}" \
    -e WAO_GIT_HISTORY=/workspace/wao-web/node_modules/.cache/wao-git-history.tsv \
    -e WAO_CHANGE_BASE="${WAO_CHANGE_BASE:-}" \
    -e WAO_GIT_RELEASE_RANGE=/workspace/wao-web/node_modules/.cache/wao-git-release-range.tsv \
    -v "$PWD:/workspace" \
    -v "$cache:/tmp/npm-cache" \
    -w "/workspace/$project" \
    "$node_image" \
    npm "$@"
}

# The job is single-threaded and only checks out trusted main. npm ci creates
# a complete tree for each package lock; preserve that tree between builds and
# rerun npm ci whenever either manifest or the pinned Node image changes.
deps_key="$(
  {
    printf '%s\n' "$node_image"
    git hash-object "$project/package.json" "$project/package-lock.json"
  } | git hash-object --stdin
)"
deps_marker="$project/node_modules/.wao-install-key"
installed_key="$(cat "$deps_marker" 2>/dev/null || true)"
if [ "$installed_key" != "$deps_key" ] \
    || [ ! -f "$project/node_modules/.package-lock.json" ] \
    || [ ! -x "$project/node_modules/.bin/vite" ]; then
  rm -f "$deps_marker"
  run_npm "$project" ci
  printf '%s\n' "$deps_key" >"$deps_marker.tmp.$$"
  mv "$deps_marker.tmp.$$" "$deps_marker"
else
  printf '%s dependencies unchanged; reusing npm ci tree.\n' "$project"
fi
if [ "$run_tests" = true ]; then
  run_npm "$project" run test:unit -- --run --maxWorkers=2
  if [ "$project" = wao-web ]; then run_npm "$project" run test:edge-static; fi
fi
if [ "$build_project" = true ]; then
  run_npm "$project" run build
fi
