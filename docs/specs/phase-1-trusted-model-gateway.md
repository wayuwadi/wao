# Phase 1：受信模型网关 API 接入

对应任务：`t1787732446010`。本需求只把固定 LiteLLM 网关用于服务端 API 功能，不把它作为 AI Run 入口。

## 用户链路

1. 设置页「AI 账号」提供「添加模型网关」，展示只读的受信 Base URL 和当前授权模型
   `deepseek-v4-flash`；Token 使用密码框录入，草稿、列表和编辑接口不回显明文。
2. 保存后可以测试连接。服务端携带 Bearer Token 请求真实 `/v1/models`；鉴权失败、响应不兼容或模型未授权
   时显示真实失败原因并允许更新 Token 后重试。
3. 最新创建且启用的模型网关或 DeepSeek API 配置标记为「文档润色」。知识库选区润色调用对应
   `/chat/completions`；处理期间不改原文，只有用户确认候选后才替换。
4. 停用或删除当前配置后顺延到下一项。没有启用的模型 API 时保留本地 Codex 润色；已有配置但调用失败时
   不静默降级，避免隐藏 Token、权限或网关故障。

## 安全与真实数据边界

- Token 复用 `ai_account.credential_json` 和 `VaultCryptoService` 加密落库，不进入 Git、前端、日志或 Runner。
- 服务端只接受完整地址
  `https://ai.xingshiapp.cn/api/model-credentials/litellm-proxy/v1`，同时锁定 scheme、host 与 path。
- 模型可用性只依据当前 Token 的真实 `/models` 返回。任务截图中的配额与掩码不转成系统额度数据。
- provider 不出现在 AI Run 账号选择、默认 Codex 账号或 Runner 凭据同步中。

## 验收

- 新建、编辑留空 Token、轮换、停用、删除和失败重试沿用 AI 账号完整状态；浏览器存储不含 Token。
- 非受信主机或同主机其他路径均被服务端拒绝；网关 Token 只发送到固定 HTTPS Base URL。
- `/models` 必须包含 `deepseek-v4-flash` 才标记连接正常；无 Token 的真实端点返回 401，不显示伪成功。
- 文档润色请求使用 Chat Completions，拒绝空结果和截断结果；模型网关不参与 AI Run。
