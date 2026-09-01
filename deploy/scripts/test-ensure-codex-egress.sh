#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
fixture="$(mktemp -d)"
cleanup() {
  rm -rf -- "${fixture}"
}
trap cleanup EXIT HUP INT TERM

config_dir="${fixture}/clash-verge"
fake_bin="${fixture}/bin"
mkdir -p "${config_dir}" "${fake_bin}"
printf 'mixed-port: 7897\n' >"${config_dir}/clash-verge.yaml"

printf '%s\n' \
  '#!/bin/sh' \
  'printf "%s\n" "$*" >"${WAO_TEST_MIHOMO_LOG}"' >"${fake_bin}/mihomo"
printf '%s\n' \
  '#!/bin/sh' \
  'printf 401' >"${fake_bin}/curl"
chmod +x "${fake_bin}/mihomo" "${fake_bin}/curl"

CODEX_MIHOMO_CONFIG_DIR="${config_dir}" \
CODEX_MIHOMO_BIN="${fake_bin}/mihomo" \
WAO_TEST_MIHOMO_LOG="${fixture}/mihomo.log" \
  sh "${script_dir}/ensure-codex-egress.sh"

grep -Fq -- "-d ${config_dir} -f ${config_dir}/clash-verge.yaml -t" "${fixture}/mihomo.log"
PATH="${fake_bin}:${PATH}" sh "${script_dir}/healthcheck-codex-egress.sh"

echo 'Codex 无桌面出站配置测试通过。'
