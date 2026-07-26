# ADR-026：AI 运行用量由 Runner 从 CLI 事件读取并随结果回传

## 状态

已采纳。

## 背景

现在能看到一次任务是 Codex 还是 Claude Code 做的，但看不到它花了多少。订阅制下「花了多少」
既是成本，也是判断这次任务该不该换个更便宜的模型的依据。要做自动选模型（后续 ADR），先得
有一份可信的账。

WAO 服务端拿不到这个数据：AI CLI 的登录态和调用都在用户电脑上，服务端只收到退出码和摘要。

## 决策

- 用量由 Runner 在本机解析，随 `POST /api/ai/runner/runs/{runId}/complete` 一起回传，
  服务端不主动向任何 AI 供应商查询。
- 数据源是各 CLI 自己吐出的结束事件，不做估算：
  - Claude Code：`result` 事件的 `usage`（`input_tokens`、`output_tokens`、
    `cache_creation_input_tokens`、`cache_read_input_tokens`）、`total_cost_usd`，
    以及按模型分组的 `modelUsage`。
  - Codex：`turn.completed` 事件的 `usage`（`input_tokens`、`cached_input_tokens`、
    `output_tokens`），逐轮累加。Codex 不回传花费。
- 落库口径：`input_tokens` 只算非缓存输入，`cached_input_tokens` 是缓存读取与缓存写入之和，
  `total_tokens` 是三者合计。`cost_usd` 只在 CLI 真的给了金额时写入。
- 一次运行可能用到多个模型（自动选模型时尤其常见）。`model` 记输出 token 最多的那个，当作
  「这次主要是谁做的」；`models` 记全部，逗号分隔，供事后核账。
- 续跑（ADR-025 的补充输入）在同一条运行记录上**累加**，页面看到的是这条运行到目前为止的
  总消耗，而不是最后一轮的消耗。
- 「没记上账」和「用了 0」必须分开：DTO 带 `recorded` 标记，前端据此显示「未回传」而不是
  `$0.00`。Codex 没有花费数据时 `costUsd` 是 null，不能编一个 0 出来。

## 后果

- 每次运行都能看到模型、token 与折算额度，任务详情里可长期回看。
- 统计口径依赖各 CLI 的事件字段。字段改名会让用量变成「未回传」，但不会让任务失败——解析
  异常一律回落成空用量，宁可少一条账，也不能让一次已经跑完的任务因为统计问题变成失败。
- Codex 侧只有 token 没有金额，跨工具的成本对比只能以 Claude 侧为准，或后续自行维护单价表。
- 服务端只信任节点回传的数字，且只允许账单变大（负数按 0 处理）；这是开发阶段可接受的信任
  模型，与 Runner 令牌的信任级别一致。
