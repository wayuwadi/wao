#!/bin/sh
set -eu

archive_path="${1:?Usage: install-release-linux.sh ARCHIVE PRIVATE_ENV SHARED_ENV CERTIFICATE IMAGES IMAGE_TAG [SNAPSHOT] [TARGET]}"
private_env_path="${2:?Private environment path is required}"
shared_env_path="${3:?Shared environment path is required}"
certificate_path="${4:?Gateway certificate path is required}"
images_path="${5:?Image archive path is required}"
image_tag="${6:?Image tag is required}"
snapshot_path="${7:-}"
target_root="${8:-${HOME}/deploy/wao}"
image_manifest_path="${9:-}"

case "${image_tag}" in
  *[!A-Za-z0-9_.-]*|'') echo 'Invalid image tag.' >&2; exit 2 ;;
esac
case "${target_root}" in
  /*) ;;
  *) echo "Target root must be an absolute path: ${target_root}" >&2; exit 2 ;;
esac
case "${target_root}" in
  /|"${HOME}") echo "Unsafe target root: ${target_root}" >&2; exit 2 ;;
esac

for required_file in \
  "${archive_path}" "${private_env_path}" "${shared_env_path}" \
  "${certificate_path}"; do
  test -f "${required_file}" || {
    echo "Release input not found: ${required_file}" >&2
    exit 1
  }
done
if [ "${images_path}" != - ]; then test -f "${images_path}"; fi
if [ -n "${image_manifest_path}" ]; then test -f "${image_manifest_path}"; fi
if [ -n "${snapshot_path}" ]; then
  test -s "${snapshot_path}" || {
    echo "Replica snapshot not found or empty: ${snapshot_path}" >&2
    exit 1
  }
fi

command -v flock >/dev/null 2>&1 || {
  echo 'flock is required for atomic Linux node deployment.' >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  echo 'Docker is unavailable to the deployment user.' >&2
  exit 1
}

umask 077
install -d "$(dirname -- "${target_root}")" "${target_root}" "${target_root}/deploy/certs"
lock_path="${HOME}/.local/state/wao-linux-deploy.lock"
install -d "$(dirname -- "${lock_path}")"
exec 9>"${lock_path}"
flock -w 600 9 || {
  echo 'Timed out waiting for the Linux deployment lock.' >&2
  exit 1
}

cleanup_inputs() {
  rm -f -- "${archive_path}" "${private_env_path}" "${shared_env_path}" \
    "${certificate_path}"
  if [ "${images_path}" != - ]; then rm -f -- "${images_path}"; fi
  if [ -n "${image_manifest_path}" ]; then rm -f -- "${image_manifest_path}"; fi
  if [ -n "${snapshot_path}" ]; then
    rm -f -- "${snapshot_path}"
  fi
}
trap cleanup_inputs EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

# deploy/ contains private configuration, certificates and persistent backup data.
# All other top-level entries belong to the immutable release and are replaced.
tar -tzf "${archive_path}" >/dev/null
if [ "${images_path}" != - ]; then gzip -t "${images_path}"; fi
if [ "${images_path}" != - ]; then gzip -dc "${images_path}" | docker load; fi

if [ -n "${image_manifest_path}" ]; then
  test "$(wc -l <"${image_manifest_path}" | tr -d ' ')" = 6
  for expected_image in \
    "wao-server:${image_tag}" "wao-agent-server:${image_tag}" \
    "wao-gateway:${image_tag}" \
    mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2; do
    manifest_id="$(awk -v image="${expected_image}" '$1 == image { print $2 }' "${image_manifest_path}")"
    printf '%s' "${manifest_id}" | grep -Eq '^sha256:[0-9a-f]{64}$'
    actual_id="$(docker image inspect --format '{{.Id}}' "${manifest_id}")"
    test "${actual_id}" = "${manifest_id}"
    docker tag "${manifest_id}" "${expected_image}"
  done
fi

find "${target_root}" -mindepth 1 -maxdepth 1 ! -name deploy -exec rm -rf -- {} +
tar -xzf "${archive_path}" -C "${target_root}"
install -m 0600 "${private_env_path}" "${target_root}/deploy/.env.secrets"
install -m 0600 "${shared_env_path}" "${target_root}/deploy/.env"
install -m 0600 "${certificate_path}" "${target_root}/deploy/certs/gateway.p12"

if [ -n "${snapshot_path}" ]; then
  sh "${target_root}/deploy/scripts/bootstrap-mysql-slave.sh" "${snapshot_path}"
fi

WAO_IMAGE_TAG="${image_tag}" SKIP_IMAGE_BUILD=true \
  sh "${target_root}/deploy/scripts/deploy-linux.sh" slave
sh "${target_root}/deploy/scripts/validate-deployment-node.sh" slave "${image_tag}"

printf 'Linux slave deployment completed: %s\n' "${image_tag}"
