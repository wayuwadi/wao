#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$script_dir"

companion_dir="$(CDPATH= cd -- "$script_dir/../.." && pwd)"
output_dir="$companion_dir/artifacts/macos"
app_dir="$output_dir/WAO Companion.app"
binary_name="WaoCompanionMac"

case "$output_dir" in
  "$companion_dir/artifacts/macos") ;;
  *) echo "Refusing unsafe output path: $output_dir" >&2; exit 1 ;;
esac
rm -rf "$output_dir"
mkdir -p "$app_dir/Contents/MacOS" "$app_dir/Contents/Resources"

if [ "${1:-}" = "--universal" ]; then
  swift build -c release --arch arm64 --scratch-path .build-arm64
  swift build -c release --arch x86_64 --scratch-path .build-x86_64
  lipo -create \
    ".build-arm64/arm64-apple-macosx/release/$binary_name" \
    ".build-x86_64/x86_64-apple-macosx/release/$binary_name" \
    -output "$app_dir/Contents/MacOS/$binary_name"
  resource_bundle="$(find .build-arm64 -type d -name 'WaoCompanionMac_WaoCompanionMac.bundle' | head -n 1)"
else
  swift build -c release
  binary_dir="$(swift build -c release --show-bin-path)"
  cp "$binary_dir/$binary_name" "$app_dir/Contents/MacOS/$binary_name"
  resource_bundle="$(find "$binary_dir" -maxdepth 1 -type d -name 'WaoCompanionMac_WaoCompanionMac.bundle' | head -n 1)"
fi

test -n "$resource_bundle"
cp -R "$resource_bundle" "$app_dir/Contents/Resources/"
cp Info.plist "$app_dir/Contents/Info.plist"
chmod 0755 "$app_dir/Contents/MacOS/$binary_name"
codesign --force --deep --sign - "$app_dir"

cd "$output_dir"
ditto -c -k --sequesterRsrc --keepParent "WAO Companion.app" WAO-Companion-macos-universal.zip
shasum -a 256 WAO-Companion-macos-universal.zip \
  | sed 's/  /  /' > WAO-Companion-macos-universal.zip.sha256
