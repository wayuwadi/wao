# ADR-169：AI Run 接入 Codex 原生目标模式

## 状态

已采纳。

## 背景

WAO 已支持直接执行和无人值守 Plan，但还不能为一次 AI Run 建立跨回合持续、具有明确终态的目标。仅在
`codex exec` 提示中写“这是目标模式”不会产生 Codex 的目标状态，也无法可靠展示暂停、阻塞、额度受限、
累计 Token 和持续时间，因此属于不可验真的演示逻辑。

Codex 当前把 `/goal` 作为应用中的交互入口，同时由 app-server 提供同一持久目标状态的
`thread/goal/set`、`thread/goal/get` 和 `thread/goal/clear` 协议。CLI 的 `exec` 即使启用 goals feature，
也不会自动把提示转换为目标。

## 决策

- Codex 派工入口提供直接执行、Plan、目标三种互斥模式；默认仍为 Plan，不改变既有用户习惯。服务端同时
  校验 `planMode && goalMode` 为非法，避免不同客户端绕过前端互斥。
- `ai_task_run` 新增 `goal_mode`、`goal_objective`、`goal_status`、`goal_tokens_used` 和
  `goal_time_used_seconds`。目标正文包含代码合并、部署成功和线上验收边界；重跑与续跑沿用原目标。
- 普通与 Plan 运行继续使用成熟的 `codex exec --json` 链路。只有目标模式启动 `codex app-server`，恢复或创建
  thread 后调用 `thread/goal/set`，再用 `turn/start` 执行；这让原生目标能力的风险限制在显式选择的运行内。
- Runner 监听 `thread/goal/updated` 和 `thread/tokenUsage/updated`，把目标状态、目标自身累计 Token 和持续时间
  结构化回传。目标没有主动进入终态时，Runner 只根据 WAO 唯一最终状态收口：SUCCESS / SKIPPED 为 complete，
  BLOCKED 或缺失状态为 blocked，不把一次模型回合结束误标为目标完成。
- AI Run 档案独立展示“Codex 原生目标”卡片，不把目标累计量混入现有运行账单；历史运行和其它引擎保持空值。

## 依据与后果

官方目标说明把目标定义为可长期运行、具有可验证停止条件的持久对象；app-server 文档明确目标接口与 `/goal`
共享同一状态。因此 WAO 不模拟斜杠命令，也不依赖不可自动化的桌面输入，而是使用官方集成协议。

目标模式依赖执行节点安装支持 goals 的 Codex CLI；协议初始化或目标建立失败时按现有自动重试和失败档案收口，
不得静默降级成提示词模式。依据：
[Codex 目标模式](https://learn.chatgpt.com/use-cases/follow-goals)、
[Codex app-server](https://learn.chatgpt.com/docs/app-server)、
[Codex 命令参考](https://learn.chatgpt.com/docs/developer-commands?surface=cli)。
