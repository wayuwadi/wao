#!/usr/bin/env bash
set -euo pipefail
: "${CLOUD_KEY_FILE:?}"
: "${CLOUD_KNOWN_HOSTS_FILE:?}"
: "${CLOUD_USER:?}"
: "${WAO_CLIENT_VERSION:?}"
[[ "$BUILD_NUMBER" =~ ^[0-9]+$ ]] || exit 2
incoming="/tmp/wao-client-${BUILD_NUMBER}-1"
options=(-i "$CLOUD_KEY_FILE" -o "UserKnownHostsFile=$CLOUD_KNOWN_HOSTS_FILE" -o StrictHostKeyChecking=yes -o BatchMode=yes -o ConnectTimeout=15)
destination="$CLOUD_USER@8.136.152.131"
ssh "${options[@]}" "$destination" "install -d '$incoming'"
scp "${options[@]}" client-release/publish/* "$destination:$incoming/"
ssh "${options[@]}" "$destination" bash -s -- "$incoming" "$WAO_CLIENT_VERSION" < wao-client/scripts/publish.sh
docker run --rm --network host --user "$(id -u):$(id -g)" \
  -v "$PWD:/workspace" -w /workspace node:24.15.0-bookworm-slim \
  node wao-client/scripts/verify-publication.mjs client-release/publish "$WAO_CLIENT_VERSION"
