#!/bin/sh
set -eu

# 与本次实现和 OpenAI 官方 changelog 验证过的稳定版本保持一致；升级时通过独立 PR 调整。
CODEX_VERSION="${WAO_CODEX_VERSION:-0.147.0}"
# Linux 主节点的 GitHub 出口并不稳定；使用 OpenAI 官方发布域名获取安装器，
# 再由 CODEX_RELEASE 固定实际安装版本，避免每次部署随 latest 漂移。
INSTALLER_URL="https://releases.openai.com/codex/install.sh"
CODEX_BIN="${HOME}/.local/bin/codex"
tmp_installer="$(mktemp)"
trap 'rm -f "${tmp_installer}"' EXIT INT TERM

curl -fsSL --connect-timeout 15 --max-time 120 "${INSTALLER_URL}" -o "${tmp_installer}"
CODEX_RELEASE="${CODEX_VERSION}" CODEX_NON_INTERACTIVE=true sh "${tmp_installer}"
"${CODEX_BIN}" --version | grep -F "${CODEX_VERSION}" >/dev/null

unit_dir="${HOME}/.config/systemd/user"
unit_file="${unit_dir}/wao-ai-runner.service"
dropin_dir="${unit_dir}/wao-ai-runner.service.d"
state_file="${HOME}/.wao/ai-runner.json"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
project_root="$(CDPATH= cd -- "${script_dir}/../.." && pwd)"

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
