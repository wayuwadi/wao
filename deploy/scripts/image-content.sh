#!/bin/sh
# 由 deploy-linux.sh / validate-deployment-node.sh 引入；调用方提供 run_docker。

image_content_fingerprint() (
  image="$1"
  content="$(run_docker image inspect --format \
    '{{.Os}}/{{.Architecture}} {{json .Config}} {{json .RootFS.Layers}}' "$image")" || return 1
  [ -n "$content" ] || return 1
  printf '%s' "$content" | openssl dgst -sha256 -r | cut -d' ' -f1
)

ensure_content_tag() (
  service="$1"
  source_image="$2"
  image_id="$(run_docker image inspect --format '{{.Id}}' "$source_image")" || return 1
  case "$image_id" in
    sha256:[0-9a-f]*) ;;
    *) echo "Unexpected ${service} image id: ${image_id}" >&2; return 1 ;;
  esac
  fingerprint="$(image_content_fingerprint "$source_image")" || return 1
  case "$fingerprint" in
    *[!0-9a-f]*|'') echo "Invalid ${service} content fingerprint" >&2; return 1 ;;
  esac
  content_tag="content-$(printf '%.12s' "$fingerprint")"
  tagged_image="${service}:${content_tag}"
  if run_docker image inspect "$tagged_image" >/dev/null 2>&1; then
    existing_fingerprint="$(image_content_fingerprint "$tagged_image")" || return 1
    [ "$existing_fingerprint" = "$fingerprint" ] || {
      echo "Content tag collision for ${tagged_image}" >&2; return 1;
    }
  else
    run_docker tag "$image_id" "$tagged_image" || return 1
  fi
  printf '%s\n' "$content_tag"
)
