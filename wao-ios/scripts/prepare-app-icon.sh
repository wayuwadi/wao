#!/bin/sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
source_icon="$repo_root/wao-web/public/apple-touch-icon.png"
target_dir="$repo_root/wao-ios/Sources/Assets.xcassets/AppIcon.appiconset"

test -f "$source_icon"
mkdir -p "$target_dir"
sips --resampleHeightWidth 1024 1024 "$source_icon" --out "$target_dir/AppIcon.png" >/dev/null
