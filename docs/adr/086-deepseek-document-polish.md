# ADR-086：DeepSeek 文档润色复用集中 AI 账号并限定官方主机

## 状态

已采纳。补充 ADR-030、ADR-032 与 ADR-041，不改变其他 AI 助手和开发任务的 Runner 路由。

## 背景

知识库已有真实的选区润色交互，但执行端只能进入本地 Codex Runner 队列。文档短文本优化并不需要仓库、
工具调用或本地执行环境；Runner 离线或忙于开发任务时，简单润色也会排队失败。用户希望使用 DeepSeek API，
并在设置页选择模型、管理 Token。

DeepSeek 当前官方 OpenAI 兼容接口是 `https://api.deepseek.com/chat/completions`，模型列表可由 `/models`
读取。2026-08-19 的官方模型为 `deepseek-v4-flash` 和 `deepseek-v4-pro`，旧的 `deepseek-chat` 与
`deepseek-reasoner` 已在 2026-07-24 停用，不能继续作为默认选项。依据：
[DeepSeek 首次 API 调用](https://api-docs.deepseek.com/quick_start/pricing-details-cny/)、
[Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion)。

## 决策

- 在既有 `ai_account` 模型中增加 `deepseek` provider；仍用 `auth_method=api_key`、已有 `base_url`、`model`
  与 `api_protocol` 字段，不新增重复的 Token 表。`api_protocol=chat_completions` 用来与 Codex 自定义端点的
  `responses` 区分。
- Token 继续由 `VaultCryptoService` 加密，前端永不回显；更新请求不带新 Token 时保留原密文。任务描述里
  出现的疑似凭据视为已经公开泄露，不能自动写入代码或数据库。
- DeepSeek 配置固定使用官方 HTTPS 主机，服务端拒绝其他 scheme、host、port、path、query 和 fragment。
  这是供应商专用配置，不复用允许任意 Base URL 的 Codex 自定义端点边界。
- 设置页提供 V4 Flash / V4 Pro 明确选择、Token 新建与轮换、启停、删除及真实 `/models` 连接测试。
  最新创建且启用的 DeepSeek 配置是文档润色默认项，并在卡片上明确标识；停用它后顺延到下一项。
- `POST /api/assistant/polish` 有可用 DeepSeek 配置时由 WAO 服务端直接调用 Chat Completions，使用关闭思考
  模式的快速请求；上游返回空内容或 `finish_reason=length` 时整体失败，不让用户用不完整候选覆盖原文。
- 没有 DeepSeek 配置时继续进入既有本地 Codex 只读队列，保证升级不破坏原链路。配置存在但请求失败时不
  静默降级，以免用户误以为测试的是所选模型。

## 后果

- 文档短文本润色不再必须占用 Runner，模型和 Token 可以在现有安全管理台中轮换。
- AI 账号仍是系统级配置；多个 DeepSeek 配置用「最新创建且启用」决定默认值。若未来需要按成员或按文档
  选择，再增加显式偏好字段，不能靠前端本地存储伪造跨设备配置。
- DeepSeek 模型命名会变化；连接测试会及时暴露已下线模型，但新增官方模型仍需在受控下拉列表和本 ADR
  的后续决策中显式加入，避免任意字符串绕过已验证能力边界。
