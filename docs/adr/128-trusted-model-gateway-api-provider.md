# ADR-128：固定可信模型网关作为服务端 API Provider

## 状态

已接受（2026-08-26）。补充 ADR-032 与 ADR-086，不改变 AI Run 的账号选择和 Runner 路由。

## 上下文

WAO 获得了一枚每日 100,000,000 Token 配额的 LiteLLM 网关密钥。网关提供 OpenAI 兼容的
`/v1/models` 与 `/v1/chat/completions`，当前密钥获授权模型为 `deepseek-v4-flash`。密钥不能进入 Git、
前端包、截图或日志；任务也明确要求暂不把网关作为 AI Run 入口。

现有 DeepSeek 文档润色已经具备服务端直调、凭据加密、真实模型检测和失败重试链路，但它有意只允许
`api.deepseek.com`。把新网关伪装成 DeepSeek 官方主机会破坏这个安全边界；把它保存成 Codex 自定义 API
又会让它出现在 AI Run 账号选择中，与当前范围冲突。

## 决策

- 新增 `model_gateway` provider，复用 `ai_account` 的 `api_key` 认证与 AES-256-GCM 加密，不新增凭据表，
  列表和编辑接口继续不回传 Token。
- Base URL 固定为醒市 AI 提供的
  `https://ai.xingshiapp.cn/api/model-credentials/litellm-proxy/v1`。服务端同时校验 HTTPS、主机与完整路径，
  拒绝端口、查询参数、片段和其他路径，防止 Token 被表单篡改后发送到非受信端点。
- 当前只允许任务提供且可由 `/models` 验证的 `deepseek-v4-flash`。连接测试必须确认真实模型列表包含该模型；
  未授权、响应格式异常或模型缺失均保存为失败状态，不伪造可用性或额度。
- 最新创建且启用的 `model_gateway` 或 DeepSeek 配置作为文档润色 API；没有这两类配置时仍回退本地 Codex。
  API 调用失败时正文保持不变并允许重试，不静默切换供应商。
- `model_gateway` 不进入 `defaultCodexRunAccount`、AI Run 账号筛选或 Runner 凭据同步。以后若要用于 AI Run，
  必须另做需求和协议/工具调用验收，不能因 `/models` 成功就推断 Responses 工具调用可用。

## 后果

- 管理员可在设置页安全录入或轮换网关 Token，并用真实 `/models` 验证权限；文档选区润色可消费共享额度。
- 仓库和部署配置不包含密钥。公开任务只提供掩码截图时，发布可以验证配置与鉴权边界，但实际成功润色仍需
  有权限的管理员在页面录入明文 Token 后执行。
- 网关路径或授权模型改变时需要显式更新受信常量和规格；这是避免任意代理地址获得集中 Token 的有意约束。
