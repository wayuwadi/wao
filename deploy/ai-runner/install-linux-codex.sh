#!/bin/sh
set -eu

# 与本次实现和 OpenAI 官方 changelog 验证过的稳定版本保持一致；升级时通过独立 PR 调整。
CODEX_VERSION="${WAO_CODEX_VERSION:-0.147.0}"
# Linux 主节点的 GitHub 出口并不稳定；使用 OpenAI 官方发布域名获取安装器，
# 再由 CODEX_RELEASE 固定实际安装版本，避免每次部署随 latest 漂移。
INSTALLER_URL="https://releases.openai.com/codex/install.sh"
CODEX_BIN="${HOME}/.local/bin/codex"
CODEX_PACKAGE="${WAO_CODEX_PACKAGE:-}"
CODEX_CHECKSUMS="${WAO_CODEX_CHECKSUMS:-}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"

install_bundled_package() {
  package="$1"
  checksums="$2"
  asset="codex-package-x86_64-unknown-linux-musl.tar.gz"
  [ "$(uname -m)" = "x86_64" ] || {
    echo "随发布包下发的 Codex 仅支持 Linux x86_64，当前架构：$(uname -m)" >&2
    return 1
  }
  expected="$(awk -v asset="${asset}" '$2 == asset && length($1) == 64 && $1 !~ /[^0-9a-fA-F]/ { print tolower($1); exit }' "${checksums}")"
  [ -n "${expected}" ] || { echo "Codex 校验清单缺少 ${asset}" >&2; return 1; }
  actual="$(sha256sum "${package}" | awk '{ print tolower($1) }')"
  [ "${actual}" = "${expected}" ] || { echo 'Codex 发布包 SHA-256 校验失败' >&2; return 1; }

  standalone_root="${HOME}/.codex/packages/standalone"
  releases_dir="${standalone_root}/releases"
  release_dir="${releases_dir}/${CODEX_VERSION}-x86_64-unknown-linux-musl"
  stage_dir="${releases_dir}/.staging.${CODEX_VERSION}"
  mkdir -p "${releases_dir}" "$(dirname -- "${CODEX_BIN}")"
  if ! "${release_dir}/bin/codex" --version 2>/dev/null | grep -F "${CODEX_VERSION}" >/dev/null; then
    rm -rf "${stage_dir}"
    mkdir -p "${stage_dir}"
    tar -xzf "${package}" -C "${stage_dir}"
    chmod 0755 "${stage_dir}/bin/codex" "${stage_dir}/bin/codex-code-mode-host" \
      "${stage_dir}/codex-path/rg" "${stage_dir}/codex-resources/bwrap"
    ln -sf bin/codex "${stage_dir}/codex"
    rm -rf "${release_dir}"
    mv "${stage_dir}" "${release_dir}"
  fi
  ln -sfn "${release_dir}" "${standalone_root}/current"
  ln -sfn "${standalone_root}/current/bin/codex" "${CODEX_BIN}"
}

if [ -n "${CODEX_PACKAGE}" ] || [ -n "${CODEX_CHECKSUMS}" ]; then
  [ -f "${CODEX_PACKAGE}" ] && [ -f "${CODEX_CHECKSUMS}" ] || {
    echo 'WAO_CODEX_PACKAGE 与 WAO_CODEX_CHECKSUMS 必须同时指向可读文件' >&2
    exit 1
  }
  install_bundled_package "${CODEX_PACKAGE}" "${CODEX_CHECKSUMS}"
else
  tmp_installer="$(mktemp)"
  trap 'rm -f "${tmp_installer}"' EXIT INT TERM
  curl -fsSL --connect-timeout 15 --max-time 120 "${INSTALLER_URL}" -o "${tmp_installer}"
  CODEX_RELEASE="${CODEX_VERSION}" CODEX_NON_INTERACTIVE=true sh "${tmp_installer}"
fi
"${CODEX_BIN}" --version | grep -F "${CODEX_VERSION}" >/dev/null

unit_dir="${HOME}/.config/systemd/user"
unit_file="${unit_dir}/wao-ai-runner.service"
dropin_dir="${unit_dir}/wao-ai-runner.service.d"
state_file="${HOME}/.wao/ai-runner.json"

mkdir -p "${unit_dir}" "${dropin_dir}"
if [ ! -f "${unit_file}" ] && [ -f "${state_file}" ]; then
  cat >"${unit_file}.tmp" <<EOF
[Unit]
Description=WAO AI Runner
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=${project_root}
ExecStart=/usr/bin/python3 ${project_root}/deploy/ai-runner/wao-ai-runner.py --server https://wao.wayuwa.cn --name "Linux 主节点" --root ${HOME}/CodeSpace --git-protocol auto --max-concurrency 2
Restart=always
RestartSec=10
RestartPreventExitStatus=78

[Install]
WantedBy=default.target
EOF
  install -m 0644 "${unit_file}.tmp" "${unit_file}"
  rm -f "${unit_file}.tmp"
fi

cat >"${dropin_dir}/10-codex.conf.tmp" <<EOF
[Service]
Environment="WAO_RUNNER_EXTRA_ENGINES=codex"
Environment="WAO_CODEX_COMMAND=${CODEX_BIN}"
Environment="WAO_SYNC_AI_CREDENTIALS=1"
EOF
install -m 0644 "${dropin_dir}/10-codex.conf.tmp" "${dropin_dir}/10-codex.conf"
rm -f "${dropin_dir}/10-codex.conf.tmp"

if [ -f "${unit_file}" ]; then
  systemctl --user daemon-reload
  if [ -f "${state_file}" ]; then
    systemctl --user enable --now wao-ai-runner
    systemctl --user restart wao-ai-runner
  fi
else
  printf '%s\n' 'Codex CLI 已安装；Linux Runner 尚未配对，请先在 WAO AI 控制台生成一次性配对码。'
fi

printf 'Codex CLI %s 已安装并完成 WAO Runner 配置。\n' "${CODEX_VERSION}"
