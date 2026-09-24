#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
trap 'rm -rf -- "$fixture"' EXIT
sed -n '/^prepare_slave_images() {$/,/^}$/p' "$script_dir/ci-deploy.sh" >"$fixture/function.sh"
test -s "$fixture/function.sh"

cat >"$fixture/run.sh" <<'SH'
set -euo pipefail
source "$FIXTURE/function.sh"
WAO_RUN_TEMP="$FIXTURE"
WAO_GIT_SHA=abc1234
docker() {
  if [ "$1" = image ] && [ "$2" = inspect ]; then
    case "$5" in
      wao-server:*) printf 'sha256:%064d\n' 1 ;;
      *) printf 'sha256:%064d\n' 2 ;;
    esac
  elif [ "$1" = save ]; then
    printf 'saved image: %s\n' "$2"
    printf '%s\n' "$*" >>"$FIXTURE/actions.log"
  else
    return 1
  fi
}
slave_ssh() {
  if [ "${MISSING:-false}" = true ] && [[ "$*" == *"sha256:$(printf '%064d' 1)"* ]]; then
    return 1
  fi
  return 0
}
prepare_slave_images
SH

FIXTURE="$fixture" bash "$fixture/run.sh"
test ! -e "$fixture/wao-images.tar.gz"
test "$(wc -l <"$fixture/wao-image-manifest.txt" | tr -d ' ')" = 7

FIXTURE="$fixture" MISSING=true bash "$fixture/run.sh"
gzip -t "$fixture/wao-images.tar.gz"
test "$(grep -c '^save ' "$fixture/actions.log")" = 1
test "$(gzip -dc "$fixture/wao-images.tar.gz" | grep -c 'saved image:')" = 1
grep -Fq 'saved image: wao-server:abc1234' <(gzip -dc "$fixture/wao-images.tar.gz")

echo 'Linux1 incremental image selection checks passed.'
