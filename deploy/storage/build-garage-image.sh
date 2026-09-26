#!/usr/bin/env bash
# Package the pinned official static binary without depending on Docker Hub.
set -Eeuo pipefail

binary="${1:?usage: build-garage-image.sh PATH_TO_OFFICIAL_LINUX_AMD64_BINARY}"
expected=ae49f8de4aaee6b5ca305f7cbdccc8cd8a29b2804aac074649f18bfb83d0a2b6
actual="$(sha256sum "$binary" | awk '{print $1}')"
[[ "$actual" == "$expected" ]] || { echo 'Garage binary SHA-256 mismatch.' >&2; exit 1; }
"$binary" --version | grep -F '2.4.1' >/dev/null || {
  echo 'Garage binary version mismatch.' >&2; exit 1;
}

staging="$(mktemp -d)"
trap 'rm -rf -- "$staging"' EXIT
install -m 0755 "$binary" "$staging/garage"
tar -C "$staging" -cf - garage | docker import - wao-garage:v2.4.1-local >/dev/null
docker image inspect wao-garage:v2.4.1-local --format '{{.Id}}'
