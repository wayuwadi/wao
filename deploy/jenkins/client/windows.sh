#!/usr/bin/env bash
set -euo pipefail
root=$(pwd -P)
image=wao-client-windows-builder:rust1.98.1-xwin0.23.1-node24.15.0
cache="${JENKINS_HOME:-$HOME}/caches/wao-client-windows"
mkdir -p "$cache" "$root/client-release/raw/windows"
docker build --network host \
  --build-arg HTTP_PROXY --build-arg HTTPS_PROXY --build-arg NO_PROXY \
  -t "$image" -f deploy/jenkins/client/Dockerfile.windows deploy/jenkins/client
docker run --rm --network host --cpus=4 --memory=6g \
  --user "$(id -u):$(id -g)" \
  -e HOME=/cache/home -e CARGO_HOME=/cache/cargo -e CARGO_TARGET_DIR=/cache/target \
  -e XWIN_CACHE_DIR=/cache/xwin -e npm_config_cache=/cache/npm \
  -e HTTP_PROXY -e HTTPS_PROXY -e NO_PROXY -e WAO_CLIENT_VERSION \
  -v "$cache:/cache" -v "$root:/workspace" "$image" bash -ceu '
    mkdir -p "$HOME"
    cd /workspace
    node --test deploy/jenkins/client/validate.test.mjs
    python3 -m unittest discover -s deploy/jenkins/client -p "test_*.py"
    cd wao-client
    npm ci
    npm test
    npm run build
    cargo xwin test --locked --release --target x86_64-pc-windows-msvc --manifest-path src-tauri/Cargo.toml --no-run
    echo "Windows 原生测试已编译；Linux 交叉构建不代表 Windows 实机测试通过。"
    rm -rf "$CARGO_TARGET_DIR/x86_64-pc-windows-msvc/release/bundle"
    npm run desktop:build -- --runner cargo-xwin --target x86_64-pc-windows-msvc --bundles nsis
    installer="$CARGO_TARGET_DIR/x86_64-pc-windows-msvc/release/bundle/nsis/WAO_${WAO_CLIENT_VERSION}_x64-setup.exe"
    test -s "$installer"
    cp "$installer" /workspace/client-release/raw/windows/
  '
