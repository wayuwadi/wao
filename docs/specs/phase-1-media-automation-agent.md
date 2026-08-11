# Phase 1：自媒体自动化 Agent（历史接入规格）

> 本文记录首次独立接入的验收背景。当前仓库与发布边界已由 [多合一规格](phase-1-unified-product-delivery.md) 和 [ADR-049](../adr/049-unified-product-monorepo-delivery.md) 取代；共享 Session、真实平台能力与安全约束继续适用，独立仓库、独立流水线和 Demo 工作台不再是当前要求。

决策见 [ADR-038](../adr/038-media-agent-shared-session-and-same-origin.md)，架构见 [技术架构](../architecture/media-automation-agent.md)。

## 目标

在 WAO 中增加“自媒体 Agent”菜单和可关闭页签，嵌入由 `wayuwacn/wao-agent` 独立构建发布的前端。Agent 使用 Java 技术路线、前后端分离，并在现有三台服务器上独立部署；常规 Agent 升级不得触发 WAO 构建或部署。

## 首版用户路径

1. 已登录用户点击 WAO 左侧“公共”分组中的“自媒体 Agent”，进入注册表生成的 `/apps/media-agent`；旧 `/media-agent` 自动重定向。
2. WAO 先探测 `/api/agent/health`，成功后加载同域 `/agent/` iframe；失败时显示错误、重试和新窗口入口。
3. 浏览器自动携带当前 `WAO_SESSION`。Agent Server 从共享 Session 读取 `wao.accountId`，无需第二次登录。
4. 首次进入时为有效 WAO 账号创建默认 Agent 工作区。
5. Demo 中可创建和更新草稿，保存状态和版本来自后端持久化；旧版本写入会提示冲突。
6. 用户启动内容运行，状态依次经过排队、生成、等待人工审核和完成；刷新后仍可恢复。
7. Demo 不调用真实模型或平台，也不展示伪造的发布成功和效果指标。

## 工程范围

### 本次包含

- `wao-agent` 独立 Vue 前端、Spring Boot 后端、测试、Agent Flyway、Compose 与 GitHub Actions。
- 同域 `/agent/` 静态部署和 `/api/agent/**` 网关路由。
- 复用 WAO `WAO_SESSION`、`wao.accountId`、`account/member`。
- WAO 菜单、顶部页签、iframe 加载/错误/重试/新窗口状态。
- 系统设置中的外部应用注册表：新增、编辑、删除应用，并全局修改名称、图标、所属分组、排序值、可见性、页面入口和健康检查地址，保存后立即生效。
- 持久化 Demo 工作台、草稿乐观锁、运行状态机和人工审核。
- Linux 主节点、Windows/WSL2 从节点、云前端节点的 Agent 独立发布与回滚。

### 本次不包含

- 独立 Agent 密码、登录页、一次性 SSO 或本地用户映射。
- Python Worker、消息队列、对象存储和按领域拆微服务。
- 真实模型付费调用、自媒体平台 OAuth 与真实发布。
- 自动追逐热点、无人审核发布和伪造效果指标。

## 状态与安全要求

- API 使用 `/api/agent/` 和 `ApiResponse<T>`。
- 除健康检查外，Agent API 都必须取得共享 Session 中的有效 ACTIVE account。
- Agent 表使用 `agent_` 前缀，Flyway 使用独立历史表，不修改 WAO migration。
- 草稿覆盖空数据、保存中、已保存、失败重试和乐观锁冲突；后端刷新不得覆盖尚未保存的本地输入。
- 运行至少覆盖 `QUEUED`、`RUNNING`、`WAITING_REVIEW`、`SUCCEEDED`、`FAILED`。
- 真实密码、Token、Cookie、私钥与节点 `.env` 不得进入 Git、日志或浏览器响应。

## 验收标准

- 登录 WAO 后从菜单进入 Agent，无二次登录；刷新和新窗口保持同一账号与业务数据。
- 未登录或 WAO Session 失效时，Agent 业务 API 返回 401，并引导重新登录 WAO。
- 草稿保存后刷新仍存在，旧版本覆盖被拒绝；运行在后端持久化推进并可人工批准。
- Agent 不可用时 WAO 其他模块可用，Agent 页显示失败与重试，不出现空白页。
- 首次未配置注册表时自动继承已有 Agent 菜单配置并放在“公共”分组；改名、调序、换组或隐藏只写系统配置，不要求重新发布 WAO。
- 新增第二个外部应用后，无需增加 WAO 路由或 Vue 页面即可显示菜单、打开独立页签并加载配置入口。
- Agent 仓库单独推送可更新两台 Agent Server 和云端 Agent Web，WAO workflow 不运行。
- WAO workflow 不覆盖 `/var/www/wao-agent/dist`，Agent workflow 不更新 WAO 静态目录或容器。
