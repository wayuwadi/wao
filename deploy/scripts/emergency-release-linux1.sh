#!/usr/bin/env bash
# Run from a separate, clean checkout on the Linux1 console when Jenkins is unavailable.
set -euo pipefail

release="${1:?Usage: emergency-release-linux1.sh FULL_MAIN_SHA [DEPLOY_ROOT]}"
target="${2:-$HOME/deploy/wao}"
source_root="$(git rev-parse --show-toplevel)"
source_root="$(cd "$source_root" && pwd -P)"
case "$release" in
  *[!0-9a-f]*|'') echo 'Expected the full main commit SHA.' >&2; exit 2 ;;
esac
[ "${#release}" -eq 40 ] || { echo 'Expected the full main commit SHA.' >&2; exit 2; }
[ "$(git -C "$source_root" rev-parse HEAD)" = "$release" ] || {
  echo 'Checkout HEAD differs from the requested release.' >&2; exit 1;
}
[ "$(git -C "$source_root" rev-parse origin/main)" = "$release" ] || {
  echo 'Release is not the fetched origin/main tip; fetch main before retrying.' >&2; exit 1;
}
[ -z "$(git -C "$source_root" status --porcelain)" ] || {
  echo 'Source checkout has local changes; refusing to build a mixed release.' >&2; exit 1;
}
target="$(cd "$target" && pwd -P)"
[ "$source_root" != "$target" ] || {
  echo 'Use a source checkout separate from the installed WAO directory.' >&2; exit 1;
}
for private_file in "$target/deploy/.env" "$target/deploy/.env.secrets" \
    "$target/deploy/certs/gateway.p12"; do
  [ -s "$private_file" ] || { echo "Missing production input: $private_file" >&2; exit 1; }
done
command -v docker >/dev/null
docker info >/dev/null
command -v flock >/dev/null

umask 077
run_temp="$(mktemp -d "${TMPDIR:-/tmp}/wao-linux1-release.XXXXXXXX")"
trap 'rm -rf -- "$run_temp"' EXIT
cd "$source_root"
export WAO_GIT_SHA="$release" WAO_RUN_TEMP="$run_temp" DEPLOY_PRODUCTION=true WAO_WEB_ONLY=false

# Run the same application tests and browser type checks before changing the
# live node. A code failure stops before the installer is called.
WAO_BACKEND_PROJECTS=all sh deploy/jenkins/ci-backend-test.sh
sh deploy/jenkins/ci-frontend-build.sh wao-web true false
sh deploy/jenkins/ci-frontend-build.sh agent-web true false
sh deploy/jenkins/ci-build-frontends.sh
# The same image/source packaging gate as Jenkins. A code or build failure stops here.
sh deploy/jenkins/ci-package.sh
install -m 0600 "$target/deploy/.env.secrets" "$run_temp/wao-secrets.env"
install -m 0600 "$target/deploy/.env" "$run_temp/wao-shared.env"
install -m 0600 "$target/deploy/certs/gateway.p12" "$run_temp/wao-gateway.p12"

manifest="$run_temp/wao-image-manifest.txt"
for image in "wao-server:$release" "wao-agent-server:$release" \
    "wao-gateway:$release" mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2; do
  image_id="$(docker image inspect --format '{{.Id}}' "$image")"
  [[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || exit 1
  printf '%s %s\n' "$image" "$image_id" >>"$manifest"
done

sh deploy/scripts/install-release-linux.sh \
  "$run_temp/wao-release.tgz" "$run_temp/wao-secrets.env" \
  "$run_temp/wao-shared.env" "$run_temp/wao-gateway.p12" \
  - "$release" '' "$target" "$manifest"

printf 'WAO_LINUX1_EMERGENCY_RELEASE_VERIFIED=%s\n' "$release"
printf 'Next: publish the same SHA through the manual emergency cloud workflow.\n'
