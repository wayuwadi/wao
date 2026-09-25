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
  docker run --rm \
    --cpus=2 --memory=4g --memory-swap=4g --pids-limit=512 \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e npm_config_cache=/tmp/npm-cache \
    -e WAO_GIT_SHA="${WAO_GIT_SHA:-}" \
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
