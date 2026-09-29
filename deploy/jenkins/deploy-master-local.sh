#!/bin/sh
set -eu

if [ "${1:-}" = "--verify-jumpserver-assets" ]; then
  project_root="${2:?project root is required}"
  case "$project_root" in ''|/) echo "Unsafe project root: $project_root" >&2; exit 2 ;; esac
  exec sudo -n sh "$project_root/deploy/jumpserver/verify-assets.sh"
fi

project_root="${1:?project root is required}"
release_archive="${2:?release archive is required}"
private_env="${3:?private env is required}"
image_tag="${4:?image tag is required}"
cloud_user="${5:-root}"
cloud_address="${6:-8.136.152.131}"
# Jenkins 仅在本批次改到对应目录时传 true（ADR-280）。
sync_jumpserver="${7:-true}"
sync_infrastructure="${8:-true}"

case "$project_root" in ''|/) echo "Unsafe project root: $project_root" >&2; exit 2 ;; esac
case "$image_tag" in *[!A-Za-z0-9_.-]*|'') echo 'Invalid image tag.' >&2; exit 2 ;; esac
test -f "$release_archive"
test -f "$private_env"
test -d "$project_root/deploy"

# deploy/ contains the private environment, certificates and persistent data.
# Everything else is a release checkout and must not retain deleted source files.
find "$project_root" -mindepth 1 -maxdepth 1 ! -name deploy -exec rm -rf -- {} +
tar -xzf "$release_archive" -C "$project_root"
install -m 0600 "$private_env" "$project_root/deploy/.env.secrets"
sh "$project_root/deploy/jumpserver/ensure-sso-secret.sh" "$project_root/deploy/.env.secrets"
# `install -m 0600` creates a fresh inode and masks the directory's default ACL.
# Restore the controller's read-only access so the next release can package the
# same private configuration without broadening access for other users.
setfacl -m u:jenkins:r "$project_root/deploy/.env.secrets"

# Linux2 只允许 Jenkins 通过此部署助手切换到 wadi。装机密钥应由该受限
# 助手在解包新版本后安装，不能从流水线直接 sudo 执行仓库中的任意脚本。
if [ "$sync_jumpserver" != false ]; then
  sh "$project_root/deploy/jumpserver/install-public-key.sh" \
    "$project_root/deploy/.jumpserver-asset-key.pub"
fi

WAO_IMAGE_TAG="$image_tag" SKIP_IMAGE_BUILD=true \
  sh "$project_root/deploy/scripts/deploy-linux.sh" master
sh "$project_root/deploy/scripts/validate-deployment-node.sh" master "$image_tag"

# Stop the former Sentinel process only after the direct Linux2 Redis client
# has passed its application readiness check. Keep its named volume for
# recovery inspection; this command does not remove persistent Redis state.
sentinel_ids="$(sudo -n docker ps -aq \
  --filter label=com.docker.compose.project=wao-master \
  --filter label=com.docker.compose.service=sentinel-master)"
for sentinel_id in $sentinel_ids; do
  sudo -n docker rm -f "$sentinel_id"
done

if [ "$sync_jumpserver" != false ]; then
  JUMPSERVER_CLOUD_USER="$cloud_user" JUMPSERVER_CLOUD_ADDRESS="$cloud_address" \
    sh "$project_root/deploy/jumpserver/deploy.sh" "$project_root"
fi

# 一次性退役基础设施清单采集器；保留发布健康监控。
if [ "$sync_infrastructure" != false ]; then
  sudo -n sh "$project_root/deploy/infrastructure/retire-overview.sh"
fi
