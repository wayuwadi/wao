#!/usr/bin/env bash
set -euo pipefail
key=/etc/jenkins/secrets/tauri-updater.key
test -s "$key" || { echo '缺少原有更新签名私钥；不得更换公钥绕过旧客户端验证。' >&2; exit 1; }
root=$(pwd -P)
docker run --rm --network host --user "$(id -u):$(id -g)" \
  -e HOME=/tmp -e WAO_CLIENT_VERSION \
  -v "$root:/workspace" -v "$key:/run/secrets/tauri-updater.key:ro" \
  -w /workspace wao-client-windows-builder:rust1.98.1-xwin0.23.1-node24.15.0 bash -ceu '
    shopt -s nullglob
    windows=(client-release/raw/windows/*-setup.exe)
    macos=(client-release/raw/macos/*.app.tar.gz)
    test "${#windows[@]}" -eq 1 && test "${#macos[@]}" -eq 1
    for artifact in "${windows[0]}" "${macos[0]}"; do
      node wao-client/node_modules/@tauri-apps/cli/tauri.js signer sign \
        --private-key-path /run/secrets/tauri-updater.key --password "" "$artifact"
    done
    python3 deploy/jenkins/client/verify-signatures.py
    node wao-client/scripts/prepare-release.mjs client-release/raw client-release/publish "$WAO_CLIENT_VERSION"
  '
