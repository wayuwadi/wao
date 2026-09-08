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
slave_user="${5:-wage}"
cloud_user="${6:-root}"
slave_address="${7:-linux1.wayuwa.cn}"
cloud_address="${8:-8.136.152.131}"
slave_port="${9:-22}"

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

WAO_IMAGE_TAG="$image_tag" SKIP_IMAGE_BUILD=true \
  sh "$project_root/deploy/scripts/deploy-linux.sh" master
sh "$project_root/deploy/scripts/validate-deployment-node.sh" master "$image_tag"
JUMPSERVER_SLAVE_USER="$slave_user" JUMPSERVER_CLOUD_USER="$cloud_user" \
  JUMPSERVER_SLAVE_ADDRESS="$slave_address" JUMPSERVER_CLOUD_ADDRESS="$cloud_address" \
  JUMPSERVER_SLAVE_PORT="$slave_port" \
  sh "$project_root/deploy/jumpserver/deploy.sh" "$project_root"

# Read-only inventory is independent of the application containers.
sudo -n sh "$project_root/deploy/infrastructure/install.sh"
