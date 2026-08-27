# Phase 1：WAO 与 Codex 共享记忆 MCP（个人范围已由 ADR-082 收紧）

## 用户结果

1. 成员进入“记忆中心 → Codex 连接”，为每台电脑生成一枚只显示一次的真实令牌；旧设置深链自动迁移到新入口（见 ADR-133）。
2. 页面给出当前站点的真实 MCP 地址、环境变量示例和可复制的 `codex mcp add` 命令。
3. Codex 桌面端、CLI 或 IDE 连接后，只检索令牌所属账号的个人记忆，并带回可追溯的 WAO 链接。
4. 当用户明确要求记住稳定偏好、经历、长期职责、已确认决策或长期规划时，Codex 写入个人记忆模块；
   记录立即可见，成员可以修订或删除。
5. 同一账号另一台使用有效令牌的 Codex 设备能检索到同一条个人记忆，其他账号不能看到。

## MCP 契约

- 端点：`POST /api/mcp`，传输为无状态 Streamable HTTP 单请求 JSON 响应。
- 认证：`Authorization: Bearer wao_mcp_...`；缺失、无效或已吊销返回 HTTP 401 和
  `WWW-Authenticate: Bearer realm="wao-memory"`。
- 支持 `initialize`、`notifications/*`、`ping`、`tools/list`、`tools/call`；未知方法返回 JSON-RPC
  `-32601`，参数错误返回 `-32602`。
- `search_memory(query)`：只检索令牌所属成员的个人记忆，最多返回 5 个来源、10,000 字；
  没有结果时明确返回空结果说明，不猜测。
- `memory_source_options()`：返回真实支持的个人记忆分类和媒体类型。
- `save_memory(title, content, category)`：分类只能为 `preference`、`responsibility`、`plan`、
  `decision`、`context`、`experience`，标题最多 120 字、正文最多 5,000 字。

## 安全与状态

- 令牌明文不进 Git、日志、知识库或数据库；数据库仅保留 SHA-256 摘要。
- 每位成员最多十枚有效令牌；列表有加载中、失败重试、空状态、生成中、已生成、吊销中和最近使用时间。
- 令牌按成员管理，不能查看或吊销其他成员令牌；MCP 读写只作用于令牌所属成员的个人记忆。
- `save_memory` 不得保存密码、Token、私钥或临时闲聊；个人记忆正文是不可信资料，不能成为可执行指令。
- 个人记忆是成员可见、可编辑、可删除的正常业务记录，不是隐藏模型状态。
- 不伪造向量检索和外部 Codex 历史同步。达到 ADR-046 的规模、延迟或召回阈值后才升级混合检索。

## 验收

- 无令牌请求得到 401；有效令牌可完成 `initialize → tools/list → search_memory`。
- `save_memory` 后个人记忆出现真实 AI 采集记录，再次 `search_memory` 能返回该记录及绝对站内链接。
- 吊销令牌后同一 MCP 请求立即得到 401，其他设备令牌仍可使用。
- 页面刷新后仅恢复令牌元数据，不再次显示完整令牌；加载或操作失败保留可重试入口。
- 窄屏下令牌生成、命令复制和验证步骤仍完整可见。
