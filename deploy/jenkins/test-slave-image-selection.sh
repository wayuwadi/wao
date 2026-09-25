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
  printf '%s\n' "$*" >>"$FIXTURE/ssh.log"
  # The remote side prints every image ID it already has, in one round trip.
  grep -Eo 'sha256:[0-9a-f]{64}' <<<"$*" | sort -u | while read -r id; do
    if [ "${MISSING:-false}" = true ] && [ "$id" = "sha256:$(printf '%064d' 1)" ]; then continue; fi
    printf '%s\n' "$id"
  done
}
prepare_slave_images
SH

FIXTURE="$fixture" bash "$fixture/run.sh"
test "$(wc -l <"$fixture/ssh.log" | tr -d ' ')" = 1
test ! -e "$fixture/wao-images.tar.gz"
test "$(wc -l <"$fixture/wao-image-manifest.txt" | tr -d ' ')" = 7

FIXTURE="$fixture" MISSING=true bash "$fixture/run.sh"
gzip -t "$fixture/wao-images.tar.gz"
test "$(grep -c '^save ' "$fixture/actions.log")" = 1
test "$(gzip -dc "$fixture/wao-images.tar.gz" | grep -c 'saved image:')" = 1
grep -Fq 'saved image: wao-server:abc1234' <(gzip -dc "$fixture/wao-images.tar.gz")

echo 'Linux1 incremental image selection checks passed.'
