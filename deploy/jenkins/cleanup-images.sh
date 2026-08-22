#!/bin/sh
set -eu

: "${WAO_GIT_SHA:?WAO_GIT_SHA is required}"
for repository in wao-server wao-agent-server wao-gateway wao-codex-egress-proxy; do
  docker image ls "$repository" --format '{{.Tag}}' | while IFS= read -r tag; do
    case "$tag" in
      "$WAO_GIT_SHA"|latest|rollback|'<none>') continue ;;
      ????????????????????????????????????????)
        case "$tag" in *[!0-9a-f]*) continue ;; esac
        docker image rm "$repository:$tag" >/dev/null 2>&1 || true
        ;;
    esac
  done
done
