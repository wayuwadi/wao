#!/bin/sh
set -eu

# Clash Verge 只负责生成和更新私有配置；生产核心由 Compose 常驻托管。
# 校验过程不打印节点、订阅或代理规则。
config_dir="${CODEX_MIHOMO_CONFIG_DIR:?CODEX_MIHOMO_CONFIG_DIR is required}"
config_file="${config_dir}/clash-verge.yaml"
mihomo_bin="${CODEX_MIHOMO_BIN:?CODEX_MIHOMO_BIN is required}"

test -x "${mihomo_bin}" || {
  echo "Codex 出站缺少 mihomo：${mihomo_bin}" >&2
  exit 1
}
test -s "${config_file}" || {
  echo "Codex 出站缺少 Clash Verge 运行配置：${config_file}" >&2
  exit 1
}

if ! "${mihomo_bin}" -d "${config_dir}" -f "${config_file}" -t >/dev/null 2>&1; then
  echo 'Codex 出站的 Clash Verge 运行配置无效。' >&2
  exit 1
fi

echo 'Codex 无桌面出站配置校验通过。'
