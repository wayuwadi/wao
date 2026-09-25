#!/bin/sh
set -eu

# 仅在修改 ReleasePoster.java 后重建。运行时 Jenkins 只有 JRE，不依赖 javac。
root="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
build_dir="$(mktemp -d)"
trap 'rm -rf -- "$build_dir"' EXIT HUP INT TERM
mkdir -p "$root/deploy/jenkins/lib"
javac --release 17 -encoding UTF-8 -d "$build_dir" \
  "$root/deploy/jenkins/ReleasePoster.java"
jar --create --file "$root/deploy/jenkins/lib/release-poster.jar" \
  -C "$build_dir" .
