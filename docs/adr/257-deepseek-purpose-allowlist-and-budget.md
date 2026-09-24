# ADR-257：DeepSeek 按用途准入与每日硬预算

日期：2026-09-22。补充 ADR-086，取代 ADR-237 中 DeepSeek 默认供所有只读业务共用的决定；受信模型网关行为保持不变。

## 问题与决定

用户希望润色直接使用 DeepSeek，避免 Codex 排队；不希望知识库或未来新增功能在不知情时消耗同一把 Key。
确认默认每日 10,000,000 Token，不限制次数。所有 DeepSeek 账号、成员和部署节点共享这一上限。

- 服务端用途白名单：POLISH（选区、小记、工作台、飞书润色）、ASSISTANT（页面和知识库问答）、
  TIANREN（卦象分析）、BACKGROUND（已登记的后台只读分析）。仅 POLISH 默认开启。
- UNKNOWN 及未登记值始终拒绝；旧队列不猜测用途。用途由可信业务入口写入 ai_assistant_run.usage_purpose，
  不从浏览器、自然语言提示词、操作 key 推断。队列发送前再次验证开关。
- 未指定能力时按用途过滤默认 DeepSeek，未获准则走既有 Codex；显式指定未获准的 DeepSeek 直接失败。
  已选择 DeepSeek 后遇到额度或上游错误不静默切换供应商。
- 管理员可在 AI 账号页调整总开关、每日上限、四类用途。每项显示已确认 Token 和请求次数。
  用户可看到预算占用与上限；草稿在当前浏览器会话中保留，保存失败可重试。

## 预算及并发

V159 添加全局策略行和按北京时间日期、用途聚合的用量表。发送每次 HTTP 请求（含重试）前，独立事务锁定
同一策略行，检查当日所有用途合计，并原子预占。网络期间释放锁；并发节点不能各自使用完整额度。

预占以两条消息 UTF-8 字节数、4096 Token 协议余量与 max_tokens 合计，使用输入 Token 的保守上界。
DeepSeek 关闭 thinking，选区 max_tokens=8192，长文本/后台=16384。
有合法 usage.total_tokens 时仅结算一次，返还多预占部分；截断结果也结算。
超时、缺失用量、节点退出等不确定情形保留预占，因此“预算占用”可能高于“已确认 Token”。
跨日返回只结算预占时的日期。改开关、改额度、切账号不会清零记录。次数仅统计。
降低上限或关闭功能不撤销已经发送的请求，后续预占立即按新配置拒绝。

供应商接口：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)。
本限制只约束经 WAO DeepSeek 客户端发送的请求，不控制同一 Key 在 WAO 之外的调用。
凭据仍使用既有加密账号存储，接口、记录与预算表均不保存明文 Key 或用户待润色正文。

## 接口

`GET/PUT /api/settings/deepseek-policy` 要求管理员会话。
PUT body：enabled、dailyTokenLimit（1..1,000,000,000）、polishEnabled、assistantEnabled、
tianrenEnabled、backgroundEnabled。响应 policy、北京时间 day、usage 数组；usage 每项包含
purpose、budgetTokens、actualTokens、requestCount。浏览器不能提交使用量或用途。

## 验收范围

覆盖默认白名单、未知用途、背景默认回退、润色队列持久用途、多线程抢占同一预算、未知失败保留、实际结算、
设置不重置用量、跨日结算和每次 HTTP 重试预占。真实 Key 由用户在设置页录入，不能以模拟结果声称已验证供应商时延。


本次本地验收：114 项后端测试、5 项前端测试、TypeScript/Vite 构建及定向 ESLint 通过。
本地新后端连接既有真实数据库，Flyway 已应用 V159；浏览器以真实管理员会话验证策略读取、总开关关闭保存、
刷新保持、恢复仅润色开启。最终每日上限为 10,000,000、其他用途关闭，未创建 DeepSeek 账号或写入 Key。
真实供应商调用及耗时未验证：用户尚未录入 Key，且滚动发布前旧节点没有预算拦截，必须待新版完成发布后再启用凭据。
