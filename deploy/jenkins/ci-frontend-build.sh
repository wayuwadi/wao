#!/bin/sh
set -eu

cache="${JENKINS_HOME:-$HOME}/.npm"
mkdir -p "$cache"

run_npm() {
  project="$1"
  shift
  docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/home \
    -e npm_config_cache=/tmp/npm-cache \
    -v "$PWD:/workspace" \
    -v "$cache:/tmp/npm-cache" \
    -w "/workspace/$project" \
    node:24.15.0-bookworm-slim \
    npm "$@"
}

run_npm wao-web ci
run_npm wao-web run test:unit -- --run
run_npm wao-web run build
run_npm agent-web ci
run_npm agent-web run test:unit -- --run
run_npm agent-web run build
