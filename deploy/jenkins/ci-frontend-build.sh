#!/bin/sh
set -eu

cache="${JENKINS_HOME:-$HOME}/.npm"
mkdir -p "$cache"

project="${1:?frontend project is required}"
run_tests="${2:?run-tests flag is required}"
build_project="${3:?build flag is required}"
case "$project" in wao-web|agent-web) ;; *) echo "Invalid frontend project: $project" >&2; exit 2 ;; esac
case "$run_tests" in true|false) ;; *) echo "Invalid run-tests flag: $run_tests" >&2; exit 2 ;; esac
case "$build_project" in true|false) ;; *) echo "Invalid build flag: $build_project" >&2; exit 2 ;; esac

web_build_version=''
if [ "$project" = wao-web ] && [ "$build_project" = true ]; then
  : "${WAO_GIT_SHA:?WAO_GIT_SHA is required for the WAO Web production build}"
  web_build_version="$(git rev-parse --short "$WAO_GIT_SHA")"
fi

if [ "$run_tests" != true ] && [ "$build_project" != true ]; then
  echo "$project skipped: no tests or production build are required."
  exit 0
fi

run_npm() {
  project="$1"
  shift
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e npm_config_cache=/tmp/npm-cache \
    -e "WAO_WEB_BUILD_VERSION=$web_build_version" \
    -v "$PWD:/workspace" \
    -v "$cache:/tmp/npm-cache" \
    -w "/workspace/$project" \
    node:24.15.0-bookworm-slim \
    npm "$@"
}

run_npm "$project" ci
if [ "$run_tests" = true ]; then
  run_npm "$project" run test:unit -- --run
fi
if [ "$build_project" = true ]; then
  run_npm "$project" run build
fi
