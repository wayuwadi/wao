#!/bin/sh
set -eu

config_dir="${WAO_CODEX_CONFIG_DIR:-/config}"
source_config="${config_dir}/clash-verge.yaml"
runtime_config="${WAO_CODEX_RUNTIME_CONFIG:-/tmp/wao-clash-verge.yaml}"
mihomo_bin="${WAO_CODEX_RUNTIME_BIN:-/usr/local/bin/verge-mihomo}"

test -s "${source_config}" || {
  echo 'Codex VPN 缺少 Clash Verge 运行配置。' >&2
  exit 1
}
grep -Eq '^mixed-port:[[:space:]]*7897([[:space:]]|$)' "${source_config}" || {
  echo 'Codex VPN 的 Clash Verge mixed-port 必须为 7897。' >&2
  exit 1
}

# 不改写宿主私有配置；只在容器临时文件中允许同一 Compose 私网访问。
cp "${source_config}" "${runtime_config}"
if grep -q '^allow-lan:' "${runtime_config}"; then
  sed -i 's/^allow-lan:.*/allow-lan: true/' "${runtime_config}"
else
  printf '\nallow-lan: true\n' >>"${runtime_config}"
fi
if grep -q '^bind-address:' "${runtime_config}"; then
  sed -i 's/^bind-address:.*/bind-address: "*"/' "${runtime_config}"
else
  printf 'bind-address: "*"\n' >>"${runtime_config}"
fi

exec "${mihomo_bin}" -d "${config_dir}" -f "${runtime_config}"
