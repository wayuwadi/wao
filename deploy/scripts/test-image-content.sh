#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
. "$script_dir/image-content.sh"

run_docker() {
  case "$1 $2" in
    'image inspect')
      if [ "$3" = --format ]; then
        case "$4" in
          '{{.Id}}') printf 'sha256:%064d\n' "$SOURCE_ID" ;;
          *)
            case "$5" in
              *:content-*)
                if [ "${COLLISION:-false}" = true ]; then
                  echo 'linux/amd64 different-config ["layer"]'
                else
                  echo 'linux/amd64 same-config ["layer"]'
                fi
                ;;
              *) echo 'linux/amd64 same-config ["layer"]' ;;
            esac
            ;;
        esac
      else
        [ "${TAG_PRESENT:-false}" = true ]
      fi
      ;;
    'tag '*|'tag') printf '%s\n' "$*" >>"$fixture/tags" ;;
    *) return 2 ;;
  esac
}

SOURCE_ID=1 TAG_PRESENT=false
export SOURCE_ID TAG_PRESENT
first="$(ensure_content_tag wao-server wao-server:first)"
test "$(wc -l <"$fixture/tags" | tr -d ' ')" = 1

# 新 SHA 的清单 ID 不同，运行配置与文件层相同：保留旧内容标签和容器。
SOURCE_ID=2 TAG_PRESENT=true
export SOURCE_ID TAG_PRESENT
second="$(ensure_content_tag wao-server wao-server:second)"
test "$first" = "$second"
test "$(wc -l <"$fixture/tags" | tr -d ' ')" = 1

COLLISION=true
export COLLISION
if ensure_content_tag wao-server wao-server:collision >"$fixture/collision.log" 2>&1; then
  echo 'Conflicting content tag was accepted.' >&2
  exit 1
fi
grep -q 'Content tag collision' "$fixture/collision.log"
echo 'Runtime content tag reuse tests passed.'
