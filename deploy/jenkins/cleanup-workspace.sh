#!/bin/sh
set -eu

if [ -n "${WAO_RUN_TEMP:-}" ]; then
  case "$WAO_RUN_TEMP" in
    "${WORKSPACE:-/nonexistent}"@tmp/wao-*) rm -rf -- "$WAO_RUN_TEMP" ;;
    *) echo "Refusing to remove unexpected WAO_RUN_TEMP: $WAO_RUN_TEMP" >&2 ;;
  esac
fi

# node_modules is keyed to the package manifests and pinned Node image by
# ci-frontend-build.sh. Retain it for the next trusted main build; npm ci
# replaces it when the key changes.
