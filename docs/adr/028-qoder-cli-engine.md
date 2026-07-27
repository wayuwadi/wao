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
- 引擎 id 与命令名分离：Runner 新增 `ENGINE_COMMANDS` 映射（`qoder` → `qodercli`），
  自动探测与 `--qoder-command` 显式指定都按命令名找可执行文件。
- Runner 用 `qodercli -p --output-format stream-json` 驱动，进展过滤、最终答复解析和
  会话恢复直接复用 Claude 的口径（`CLAUDE_STYLE_ENGINES`），不复制第二份解析逻辑：
  - 只回传 assistant 文本、TodoWrite 计划与阶段/结果；thinking 与工具调用细节不上传。
  - 最终答复取 `result` 事件；`is_error` 为真时即使退出码为 0 也判失败；
    仍要求以 `WAO_RUN_STATUS: SUCCESS/SKIPPED/BLOCKED` 开头。
  - 续跑用 `-r <session-id>` 在原 worktree 恢复会话。
- 权限档位映射：`read-only` → `--permission-mode plan`，`workspace-write` →
  `--permission-mode accept_edits`，`danger-full-access` → `--dangerously-skip-permissions`。
  qodercli 以启动目录为主信任目录，worktree 的 `.git` 指向主仓库，须用 `--add-dir` 一并信任。
- worktree 仍由 Runner 按 ADR-023 管理（`.wao-runs/<run-id>` + 主克隆游离 HEAD），
  不使用 qodercli 自带的 `--worktree`：后者会绕开保留期回收和续跑派回原机的机制。
- 用量口径：Qoder 按 Credits 配额计费，`result` 事件没有按次金额与 token 字段时回落成
  空账（与 ADR-026「解析失败只是不记账」一致），模型名仍从 `system/init` 事件记录。

## 后果

- 装了 qodercli 并登录的任何电脑都能作为 Qoder 执行节点接入，与 Codex / Claude Code
  同机共存，一次心跳同时声明多种能力。
- 首次真实接入时需验证两个文档未明示的行为：`qodercli -p` 不带参数时是否从 stdin 读
  提示词、stream-json 是否要求额外参数；若与 Claude 行为不一致，只需调整 `run_qoder`
  一个函数。
- Qoder 的运行记录没有 token 与金额，用量看板中该引擎只有模型与次数维度；如果后续
  qodercli 在结束事件中补充用量字段，现有 Claude 解析器会自动把账记上。
