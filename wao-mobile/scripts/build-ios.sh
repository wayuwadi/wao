#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${MOBILE_VERSION:=$(node -p 'require("./package.json").version')}"
export MOBILE_VERSION
export IOS_ONLY=true
npm run version:native
mkdir -p artifacts
swiftc ios/App/Shared/CaptureFormat.swift tests/NativeCaptureFormatTest.swift -o artifacts/capture-format-test
artifacts/capture-format-test
xcodebuild -project ios/App/App.xcodeproj -scheme App -configuration Release -destination 'generic/platform=iOS Simulator' -derivedDataPath artifacts/simulator CODE_SIGNING_ALLOWED=NO build
xcodebuild -project ios/App/App.xcodeproj -scheme App -configuration Release -destination 'generic/platform=iOS' -archivePath "$PWD/artifacts/WAO.xcarchive" CODE_SIGNING_ALLOWED=NO archive
mkdir -p artifacts/Payload
test -d artifacts/WAO.xcarchive/Products/Applications/App.app/PlugIns/WAOShare.appex
cp -R artifacts/WAO.xcarchive/Products/Applications/App.app artifacts/Payload/
(cd artifacts && ditto -c -k --keepParent Payload "WAO-${MOBILE_VERSION}-ios-unsigned.ipa")
echo 'iOS IPA 已构建，必须个人签名后才能安装；此文件不是可直接安装或 TestFlight 包。'
