#!/bin/sh
set -eu

if [ -n "${WAO_RUN_TEMP:-}" ]; then
  case "$WAO_RUN_TEMP" in
    "${WORKSPACE:-/nonexistent}"@tmp/wao-*) rm -rf -- "$WAO_RUN_TEMP" ;;
    *) echo "Refusing to remove unexpected WAO_RUN_TEMP: $WAO_RUN_TEMP" >&2 ;;
  esac
fi

# 精确删除当前工作区生成的依赖目录；发布源码由下一次 checkout 重新取得。
for directory in wao-web/node_modules agent-web/node_modules; do
  if [ -d "$directory" ]; then rm -rf -- "$directory"; fi
done
