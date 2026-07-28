# ADR-030：集中管理 AI 工具账号凭据

## 状态

已采纳。扩展 ADR-021，允许在 WAO 服务端集中存储 AI 工具账号，但保持 Runner 凭据下发 opt-in。

## 背景

目前 Codex / Claude Code / Qoder 的登录态分散在各台执行电脑上：

- 每台电脑都要单独登录一次 AI CLI。
- 账号额度/状态只能在各自电脑上查看，没有统一视图。
- 后续希望在 Linux 主节点直接托管 AI 运行，需要先把账号凭据集中到服务器再下发给该节点。

ADR-021 曾规定「WAO 不接收 Codex、Claude Code、OpenAI、Anthropic 或 GitHub 凭据」，
其出发点是防止数据库泄露导致凭证失窃。随着 Linux 主节点成为默认执行环境，
完全不接受凭据反而迫使我们继续把登录态放在个人电脑上，与「在服务器上统一托管」的目标冲突。

## 决策

- 新增 `ai_account` 表，集中存储 Claude Code / Codex / Qoder 账号的展示信息、邮箱/标识和凭据 JSON。
  凭据用服务端已有的 `VaultCryptoService`（AES-256-GCM，密钥来自 `WAO_VAULT_KEY`）加密落库，
  前端接口永远不回传明文。
- 设置页新增「AI 账号」标签页，支持增删改、启用/停用、手动刷新额度快照。
- 额度/余额查询只使用真实存在的供应商接口：
  - Claude：当配置了 `wao.ai.claude.usage-url` 时，用凭据中的 access token 调用该地址并保存原始响应。
  - Codex / ChatGPT、Qoder：没有可靠、官方、稳定的程序化余额接口，直接标记为 `NO_API`，
    只从凭据或 JWT 中解析邮箱/标识，**不伪造余额数字**。
- Runner 凭据同步为 opt-in：通过 `--sync-ai-credentials` 或环境变量 `WAO_SYNC_AI_CREDENTIALS=1` 开启。
  开启后 Runner 每 5 分钟从 `/api/ai/runner/accounts` 拉取启用账号的明文凭据，写入本机已知的 CLI 凭据文件路径：
  - Claude Code：`~/.claude/.credentials.json`
  - Codex：`~/.codex/auth.json`
  - Qoder：目前没有已知稳定路径，除非通过 `WAO_AI_CREDENTIALS_PATH_QODER` 显式覆盖，否则跳过。
- Runner 端凭据写入使用 0o600 权限；Windows 不设置文件权限。

## 后果

- 用户只需在 WAO 设置页粘贴一次凭据，即可让多台 Runner（尤其是 Linux 主节点）共用同一账号。
- 服务端持有凭据明文解密能力，因此数据库 + `WAO_VAULT_KEY` 泄露会导致 AI 账号失窃；
  这与 ADR-021 的原始安全假设不同，需通过环境密钥管理和最小权限运行来补偿。
- 额度显示以「原始快照 + 同步状态」呈现，不会给出虚假的余额数字。
- 未开启凭据同步的 Runner 保持原有本地登录方式，不受影响。
