# ADR-028：AI Run 接入 Qoder CLI 作为第三种执行引擎

## 状态

已采纳。补充 ADR-022，走它预留的「新增 engine 枚举值 + Runner 一个分支」扩展路径，
不改变中央队列与本地 Runner 之间的协议。

## 背景

ADR-022 把「AI 工具（engine）」做成了开放维度，当时只有 `codex` 与 `claude` 两个取值。
团队日常开发已经在用 Qoder，Qoder CLI（`qodercli`）具备接入所需的全部形态：`-p` 非交互
Print 模式、`--output-format stream-json` 流式事件、`-r <session-id>` 会话恢复，且事件
schema 与 Claude Code 同构（`system/init`、`assistant`、`result` 事件，`result` 带
`session_id` / `is_error` / `result` 字段，权限跳过参数同名 `--dangerously-skip-permissions`）。

## 决策

- `ENGINES` 增加 `qoder`，前端 `AiEngine` 类型与 AI 控制台选择器同步扩展。协议、数据库
  结构、配对与心跳流程零改动：`ai_runner_node.engines` 本就是节点自述的逗号分隔字符串。
- 引擎 id 与命令名分离：Runner 新增 `ENGINE_COMMANDS` 候选映射（`qoder` → `qodercli` /
  `qoderclicn`，国际版与国内版命令名不同，按顺序探测第一个存在的），
  `--qoder-command` 可显式指定完整路径。
- Runner 用 `qodercli -p --output-format stream-json` 驱动，进展过滤、最终答复解析和
  会话恢复直接复用 Claude 的口径（`CLAUDE_STYLE_ENGINES`），不复制第二份解析逻辑：
  - 只回传 assistant 文本、TodoWrite 计划与阶段/结果；thinking 与工具调用细节不上传。
  - 最终答复取 `result` 事件；`is_error` 为真时即使退出码为 0 也判失败；
    仍要求以 `WAO_RUN_STATUS: SUCCESS/SKIPPED/BLOCKED` 开头。
  - 续跑用 `-r <session-id>` 在原 worktree 恢复会话。
- 权限档位映射：`read-only` → `--permission-mode default`（qodercli 1.1.5 没有 plan 档，
  非交互模式下 default 无人批准写操作，效果等同只读），`workspace-write` →
  `--permission-mode accept_edits`，`danger-full-access` → `--dangerously-skip-permissions`。
  qodercli 以启动目录为主信任目录，worktree 的 `.git` 指向主仓库，须用 `--add-dir` 一并信任。
- worktree 仍由 Runner 按 [ADR-023：主克隆游离 HEAD](023-ai-runner-detached-primary-clone.md) 管理（`.wao-runs/<run-id>` + 主克隆游离 HEAD），
  不使用 qodercli 自带的 `--worktree`：后者会绕开保留期回收和续跑派回原机的机制。
- 用量口径：Qoder 按 Credits 配额计费，但实测 `result` 事件带完整的 `usage` /
  `total_cost_usd` / `modelUsage` 字段，现有 Claude 解析器直接记账；字段缺失时仍回落成
  空账（与 ADR-026「解析失败只是不记账」一致）。

## 实测修订（2026-07-27，qoderclicn 1.1.5）

首次在真实机器上验证了当初留下的两个待实测点，并修正了两处与文档不符的假设：

- ✅ `-p` 不带参数时从 stdin 读提示词；`--output-format stream-json` 无需额外参数，
  事件序列（`system/init` → `assistant` → `result`）与 Claude Code 完全同构。
- 🔧 国内版命令名是 `qoderclicn`，`ENGINE_COMMANDS` 改为候选列表。
- 🔧 `--permission-mode` 实际可选值为 `default/accept_edits/bypass_permissions/dont_ask/auto`，
  文档中的 `plan` 不存在，`read-only` 改映射 `default`。
- ➕ `result` 事件实际携带 `usage` / `total_cost_usd` / `modelUsage`，用量能正常入账，
  好于当初「只记模型」的预期。

## 后果

- 装了 qodercli（或国内版 qoderclicn）并登录的任何电脑都能作为 Qoder 执行节点接入，
  与 Codex / Claude Code 同机共存，一次心跳同时声明多种能力。
- 未登录时 `result` 事件会返回 `is_error: true` 与「Not logged in」，现有解析器会判为
  失败并把原因回传到控制台，不会静默卡死。
