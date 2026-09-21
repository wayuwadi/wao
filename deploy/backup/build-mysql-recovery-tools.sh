#!/usr/bin/env bash
# Supply the official signed RPM locally when the node cannot reach package mirrors.
set -Eeuo pipefail
umask 077
package="$(realpath "${1:?Supply mysql-community-client-9.7.0-1.el9.x86_64.rpm}")"
expected=e58f5fd04fd939fe14296e86b662a55379d4ead149d1d3acbbcb65178597b5cf
[[ "$(sha256sum "$package" | cut -d' ' -f1)" == "$expected" ]] || { echo 'Unexpected recovery client package digest'; exit 1; }
stage="$(mktemp -d)"
trap 'rm -rf -- "$stage"' EXIT
docker run --rm --network none --volume "$package:/tmp/client.rpm:ro" --entrypoint sh mysql:9.7.0 -c \
  'rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-mysql && rpm --checksig /tmp/client.rpm'
docker run --rm --network none --volume "$package:/tmp/client.rpm:ro" --entrypoint rpm2cpio mysql:9.7.0 /tmp/client.rpm >"$stage/client.cpio"
(cd "$stage"; cpio -id --no-absolute-filenames './usr/bin/mysqlbinlog' <client.cpio)
install -m 0755 "$stage/usr/bin/mysqlbinlog" "$stage/mysqlbinlog"
cp "$(dirname "$0")/Dockerfile.mysql-recovery-tools" "$stage/Dockerfile"
rm "$stage/client.cpio"
DOCKER_BUILDKIT=0 docker build --pull=false -t wao-mysql-recovery-tools:9.7.0 "$stage"
docker run --rm --network none wao-mysql-recovery-tools:9.7.0 --version
