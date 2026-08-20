# ADR-091：AI Run 固化单次 Codex 模型参数

## 状态

已采纳。

## 背景

AI Runner 原先只执行 `codex exec`，模型、推理强度和服务档位都继承执行电脑的全局 Codex 配置。
同一台 Linux 主节点同时承载桌面对话与后台任务时，用户切换桌面设置会让之后的 AI Run 静默改变；
已经进入队列的运行也无法从后台确认将使用什么参数。集中 OAuth 账号又使用隔离 `CODEX_HOME`，与本机
登录态的继承结果并不一致。

## 决策

- 服务端维护并返回 AI Run 可选的 Codex 模型和推理强度。首批模型为 GPT-5.6 Sol、Terra、Luna，
  推理强度为 `medium / high`，默认 `gpt-5.6-sol + medium`。
- 任务入队时校验并固化 `requested_model / reasoning_effort`，Runner 领取时一并取得；重跑沿用原值。
- Runner 显式传递 `--model`、`model_reasoning_effort` 与 `service_tier=default`，后台开发任务不继承
  桌面端配置，也不启用 Fast。
- 自定义 Responses API 账号的模型仍由账号配置决定，Runner 不叠加官方模型参数。
- 历史运行、Claude、Qoder 保持空值和原行为。

## 结果

用户在每次派工前都能看到并选择实际参数，运行详情可以审计预期模型；执行电脑本地配置变化不再影响
新运行。新增模型或推理强度必须先更新服务端允许列表与验收测试，避免前端任意字符串进入 Runner 命令。
