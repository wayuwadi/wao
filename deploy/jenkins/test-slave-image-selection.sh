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
    if [ "$4" != '{{.Id}}' ]; then
      printf 'linux/amd64 {"image":"%s"} ["layer"]\n' "$5"
      return
    fi
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
  if [ "${REMOTE_EVAL:-false}" = true ]; then
    (
      docker() {
        if [ "$1" != image ] || [ "$2" != inspect ]; then return 1; fi
        if [ "$3" != --format ]; then
          [ "$3" != "sha256:$(printf '%064d' 1)" ]
          return
        fi
        if [ "$4" = '{{.Id}}' ]; then
          printf 'sha256:%064d\n' 3
        else
          printf 'linux/amd64 {"image":"%s"} ["layer"]\n' "${5%%:content-*}:abc1234"
        fi
      }
      eval "${*: -1}"
    )
    return
  fi
  while read -r image id fingerprint tag; do
    if [ "${MISSING:-false}" = true ] && [ "$image" = wao-server:abc1234 ]; then continue; fi
    if [ "${REUSE:-false}" = true ] && [ "$image" = wao-server:abc1234 ]; then
      printf 'content %s sha256:%064d %s\n' "$tag" 3 "$fingerprint"
      continue
    fi
    printf 'id %s\n' "$id"
  done <"$FIXTURE/wao-image-desired.txt"
}
prepare_slave_images
SH

FIXTURE="$fixture" bash "$fixture/run.sh"
test "$(wc -l <"$fixture/ssh.log" | tr -d ' ')" = 1
test ! -e "$fixture/wao-images.tar.gz"
test "$(wc -l <"$fixture/wao-image-manifest.txt" | tr -d ' ')" = 6
! grep -q 'wao-codex-egress-proxy' "$fixture/wao-image-manifest.txt"

FIXTURE="$fixture" REUSE=true bash "$fixture/run.sh"
test ! -e "$fixture/wao-images.tar.gz"
grep -Fqx "wao-server:abc1234 sha256:$(printf '%064d' 3)" "$fixture/wao-image-manifest.txt"

FIXTURE="$fixture" REMOTE_EVAL=true bash "$fixture/run.sh"
test ! -e "$fixture/wao-images.tar.gz"
grep -Fqx "wao-server:abc1234 sha256:$(printf '%064d' 3)" "$fixture/wao-image-manifest.txt"

FIXTURE="$fixture" MISSING=true bash "$fixture/run.sh"
gzip -t "$fixture/wao-images.tar.gz"
test "$(grep -c '^save ' "$fixture/actions.log")" = 1
test "$(gzip -dc "$fixture/wao-images.tar.gz" | grep -c 'saved image:')" = 1
grep -Fq 'saved image: wao-server:abc1234' <(gzip -dc "$fixture/wao-images.tar.gz")

echo 'Linux1 incremental image selection checks passed.'
