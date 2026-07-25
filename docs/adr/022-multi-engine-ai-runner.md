# ADR-022：AI Run 支持多种本地 AI 工具，并允许按次指定电脑

## 状态

已采纳。补充 ADR-021，不改变「中央队列 + 本地 Runner」的基本结构。

## 背景

ADR-021 落地后，队列只认一种执行方式：Windows 电脑上的 `codex exec`。但 Linux 主节点上
登录的是 Claude Code，同一个仓库同一批任务，其实可以由任意一台机器上的任意一种 AI CLI
完成。原来的模型没有「用哪种工具跑」这个维度：项目只能配一台首选电脑，任务只能等 Codex。

同时，页面上与 AI 执行相关的信息分散在三处：首页看运行状态、项目设置里配工作流、执行电脑
只在首页顶部露出一行小标签，配对码藏在项目设置里。想知道「哪台机器装了什么、现在忙不忙」
要在两个页面之间来回跳。

## 决策

- 引入「AI 工具（engine）」维度，当前取值 `codex` 与 `claude`（Claude Code CLI）。
  - `ai_runner_node.engines` 保存这台电脑装了哪些 CLI，逗号分隔，由 Runner 自己声明并在
    每次心跳刷新；没有声明过的老节点按 `codex` 处理。
  - `ai_project_config.default_engine` 是项目默认工具，`ai_task_run.engine` 是这次运行
    实际使用的工具。
- 引入「按次指定电脑」：`ai_task_run.requested_runner_id`。优先级高于项目的
  `preferred_runner_id`；两者都为空才由任意在线且装了该工具的电脑领取。
- 领取时同时满足三个条件才发任务：节点装了该 engine、节点是被指定的那台（如果指定了）、
  本机与项目并发都还有空位。入队时若没有任何电脑接入该工具，直接返回可理解的 400，不产生
  半条运行记录。
- Runner 用同一套协议驱动两种 CLI：
  - Codex：`codex exec --json`，最终答复取 `--output-last-message` 文件。
  - Claude Code：`claude -p --output-format stream-json --verbose`，最终答复取 `result`
    事件的 `result` 字段；`is_error` 为真时即使退出码为 0 也判失败。
  - 两种工具都要求最终答复以 `WAO_RUN_STATUS: SUCCESS/BLOCKED` 开头，退出码之外再加一道
    业务完成校验。
  - 进展过滤口径一致：只回传 AI 消息、计划（Claude 侧取 TodoWrite 的待办列表）和阶段/结果；
    命令执行、文件改动、工具调用细节不进 WAO，Claude 的 thinking 正文同样不上传。
- 首页升级为完整的 AI 控制台，三个视图整合原本分散的信息：运行状态、执行电脑（含配对码与
  启动命令）、AI 工作流（各项目配置就地编辑 + 运行统计）。项目设置里的 AI Run 区块保留，
  作为项目上下文里的快捷入口。
- 任务详情的「AI run」不再直接入队，而是先弹出选择面板：选 AI 工具、选执行电脑（默认取项目
  配置），确认后才入队。

## 后果

- 同一个仓库可以同时由 Windows 上的 Codex 和 Linux 上的 Claude Code 并行推进，两边各自
  在独立 worktree 里工作，冲突仍靠「一任务一 PR + 从最新 main 开始」约束。
- 电脑能力变成节点自述：装了什么就报什么，WAO 不需要为新机器改代码；将来加第三种 CLI 只
  需要扩 `ENGINES` 常量和 Runner 的一个分支。
- 指定电脑离线时任务会停在队列里等它上线，这是刻意的——用户明确点名了机器，静默改派会让
  「同一台机器上的登录态和本地环境」这个前提失效。
- 运行记录多了 engine 维度，历史数据默认按 `codex` 回填，旧记录展示不受影响。
