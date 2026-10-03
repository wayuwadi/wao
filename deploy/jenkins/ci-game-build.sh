#!/bin/sh
set -eu
game="${1:?game or install is required}"
case "$game" in install|dino|test:dino) ;; *) echo 'Unknown game build target' >&2; exit 2 ;; esac
cache="${JENKINS_HOME:-$HOME}/.npm"
mkdir -p "$cache"
image='node:24.15.0-bookworm-slim'
run_npm() {
  docker run --rm --cpus=2 --memory=4g --memory-swap=4g --pids-limit=512 \
    --user "$(id -u):$(id -g)" -e HOME=/tmp/home -e npm_config_cache=/tmp/npm-cache \
    -v "$PWD:/workspace" -v "$cache:/tmp/npm-cache" -w /workspace/wao-game "$image" npm "$@"
}
key="$( { printf '%s\n' "$image"; git hash-object wao-game/package.json wao-game/package-lock.json; } | git hash-object --stdin)"
marker=wao-game/node_modules/.wao-install-key
if [ "$(cat "$marker" 2>/dev/null || true)" != "$key" ] || [ ! -x wao-game/node_modules/.bin/vite ]; then
  run_npm ci
  printf '%s\n' "$key" >"$marker"
fi
case "$game" in
  install) ;;
  test:dino) run_npm run test:dino; run_npm run lint:dino ;;
  dino) run_npm run build:dino ;;
esac
