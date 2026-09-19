#!/usr/bin/env bash
set -euo pipefail
test "$(uname -s)" = Darwin
export PATH="$HOME/.cargo/bin:$PATH"
command -v node npm cargo rustup xcodebuild codesign lipo hdiutil >/dev/null
rustup target add aarch64-apple-darwin x86_64-apple-darwin
cd wao-client
npm ci
npm test
npm run build
cargo test --locked --manifest-path src-tauri/Cargo.toml
rm -rf src-tauri/target/universal-apple-darwin/release/bundle
npm run desktop:build -- --target universal-apple-darwin --bundles dmg
bundle=src-tauri/target/universal-apple-darwin/release/bundle
app="$bundle/macos/WAO.app"
lipo "$app/Contents/MacOS/wao-client" -verify_arch x86_64 arm64
codesign --verify --deep --strict --verbose=2 "$app"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")" = "$WAO_CLIENT_VERSION"
mkdir -p ../client-release/raw/macos
shopt -s nullglob
dmgs=("$bundle"/dmg/*.dmg)
test "${#dmgs[@]}" -eq 1
cp "${dmgs[0]}" ../client-release/raw/macos/
# 原生构建节点不持有更新私钥；归档在 Linux2 上统一签名。
COPYFILE_DISABLE=1 tar -czf ../client-release/raw/macos/WAO.app.tar.gz -C "$bundle/macos" WAO.app
