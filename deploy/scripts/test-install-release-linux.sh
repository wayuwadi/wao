#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
workspace="$(mktemp -d)"
trap 'rm -rf -- "${workspace}"' EXIT HUP INT TERM

release_root="${workspace}/release"
target_root="${workspace}/target"
input_root="${workspace}/incoming"
fake_bin="${workspace}/bin"
log_file="${workspace}/actions.log"
mkdir -p "${release_root}/deploy/scripts" "${target_root}/deploy/persistent" \
  "${input_root}" "${fake_bin}"
printf '%s\n' stale >"${target_root}/stale-file"
printf '%s\n' keep >"${target_root}/deploy/persistent/keep"
printf '%s\n' release >"${release_root}/release-marker"

for helper in deploy-linux.sh validate-deployment-node.sh bootstrap-mysql-slave.sh; do
  printf '%s\n' '#!/bin/sh' 'printf "%s\\n" "$0 $*" >>"$WAO_INSTALL_TEST_LOG"' \
    >"${release_root}/deploy/scripts/${helper}"
  chmod +x "${release_root}/deploy/scripts/${helper}"
done

cat >"${fake_bin}/docker" <<'EOF'
#!/bin/sh
set -eu
case "${1:-}" in
  info) exit 0 ;;
  load) cat >/dev/null; printf '%s\n' 'docker load' >>"$WAO_INSTALL_TEST_LOG" ;;
  image)
    test "${2:-}" = inspect
    case "${5:-}" in
      "${WAO_INSTALL_TEST_MISSING_ID:-never}") exit 1 ;;
      sha256:*) printf '%s\n' "$5" ;;
      *) exit 1 ;;
    esac
    ;;
  *) printf '%s\n' "docker $*" >>"$WAO_INSTALL_TEST_LOG" ;;
esac
EOF
chmod +x "${fake_bin}/docker"
cat >"${fake_bin}/flock" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod +x "${fake_bin}/flock"

tar -czf "${input_root}/release.tgz" -C "${release_root}" .
printf '%s\n' images | gzip >"${input_root}/images.tar.gz"
printf '%s\n' private >"${input_root}/private.env"
printf '%s\n' shared >"${input_root}/shared.env"
printf '%s\n' certificate >"${input_root}/gateway.p12"

PATH="${fake_bin}:${PATH}" WAO_INSTALL_TEST_LOG="${log_file}" \
  sh "${script_dir}/install-release-linux.sh" \
    "${input_root}/release.tgz" "${input_root}/private.env" \
    "${input_root}/shared.env" "${input_root}/gateway.p12" \
    "${input_root}/images.tar.gz" abc1234 '' "${target_root}"

test ! -e "${target_root}/stale-file"
test -f "${target_root}/release-marker"
grep -Fqx keep "${target_root}/deploy/persistent/keep"
grep -Fqx private "${target_root}/deploy/.env.secrets"
grep -Fqx shared "${target_root}/deploy/.env"
grep -Fqx certificate "${target_root}/deploy/certs/gateway.p12"
grep -Fq 'deploy-linux.sh slave' "${log_file}"
grep -Fq 'validate-deployment-node.sh slave abc1234' "${log_file}"
! grep -Fq 'bootstrap-mysql-slave.sh' "${log_file}"
test ! -e "${input_root}/release.tgz"
test ! -e "${input_root}/images.tar.gz"

image_id="sha256:$(printf '%064d' 1)"
manifest="${input_root}/images.txt"
for image in \
  wao-server:abc1234 wao-agent-server:abc1234 \
  wao-gateway:abc1234 wao-codex-egress-proxy:abc1234 \
  mysql:9.7.0 redis:7-alpine jeessy/ddns-go:v6.17.2; do
  printf '%s %s\n' "$image" "$image_id" >>"$manifest"
done
tar -czf "${input_root}/release.tgz" -C "${release_root}" .
printf '%s\n' private >"${input_root}/private.env"
printf '%s\n' shared >"${input_root}/shared.env"
printf '%s\n' certificate >"${input_root}/gateway.p12"
PATH="${fake_bin}:${PATH}" WAO_INSTALL_TEST_LOG="${log_file}" \
  sh "${script_dir}/install-release-linux.sh" \
    "${input_root}/release.tgz" "${input_root}/private.env" \
    "${input_root}/shared.env" "${input_root}/gateway.p12" \
    - abc1234 '' "${target_root}" "$manifest"
test ! -e "$manifest"
test "$(grep -c '^docker tag ' "$log_file")" = 7
test "$(grep -c '^docker load$' "$log_file")" = 1

tar -czf "${input_root}/release.tgz" -C "${release_root}" .
printf '%s\n' private >"${input_root}/private.env"
printf '%s\n' shared >"${input_root}/shared.env"
printf '%s\n' certificate >"${input_root}/gateway.p12"
printf '%s %s\n' wao-server:abc1234 "$image_id" >"$manifest"
printf '%s\n' preserve >"${target_root}/preserve-on-failure"
if PATH="${fake_bin}:${PATH}" WAO_INSTALL_TEST_LOG="${log_file}" \
  sh "${script_dir}/install-release-linux.sh" \
    "${input_root}/release.tgz" "${input_root}/private.env" \
    "${input_root}/shared.env" "${input_root}/gateway.p12" \
    - abc1234 '' "${target_root}" "$manifest"; then
  echo 'Incomplete image manifest must fail.' >&2
  exit 1
fi
test -f "${target_root}/preserve-on-failure"

echo 'Linux release installer tests passed.'
