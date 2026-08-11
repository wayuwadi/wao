# ADR-049：WAO 产品能力统一进入主仓库和主发布链路

## 状态

已采纳，取代 ADR-037、ADR-038 中“自媒体 Agent 分仓、独立发布”的决策，并收回 wao-game、wao-insight 的独立发布单元。共享 Session、Agent 独立进程和外部应用注册表等未冲突部分继续有效。

## 上下文

WAO 曾按产品边界拆出 `wayuwacn/wao-agent`、`wayuwacn/wao-game` 和 `wayuwacn/wao-insight`。这种方式降低了单次构建范围，但一次功能维护需要在多个仓库、工作流和部署目录之间切换，实际人工协调成本已经高于构建收益。当前团队由 AI 执行工程工作，仓库大小和技术混合不再是优先约束，减少人工介入和交付遗漏更重要。

三个独立工程的真实程度也不同：Agent 已有持久化热点采集、模型生成、素材、官方平台 OAuth 与发布链路；游戏中心有 2048 和记忆翻牌；Insight 只有演示数据和尚不存在的 `/api/insight/**` 契约。统一不能把演示数据冒充真实能力。

## 决策

- `wao` 成为唯一持续维护的产品仓库和发布入口。Agent 源码从 `wayuwacn/wao-agent@3d1a69ab404503dcfcea5a3c3a8946a650f27775` 导入 `agent-web/` 与 `agent-server/`。
- 根 Maven reactor、Dockerfile、双节点 Compose 和 main CD 同时构建、测试、发布 WAO Server、Gateway 与 Agent Server；云节点一次发布 WAO Web 和 Agent Web。
- Agent 继续使用独立 Java 进程、`/api/agent/**` 路由、`agent_` 表前缀与 `flyway_agent_schema_history`。这是运行负载和回滚边界，不再代表独立仓库或独立交付；发生明确瓶颈后才另写 ADR 调整。
- CD 从旧 Agent 节点私有配置中迁移 `AGENT_*` 与 `DOUYIN_*`，不把 Secret 写入 Git 或日志。首次部署精确接管旧 Compose 容器，并用原卷名挂载 Agent 媒体，避免 8081 端口冲突或切换时丢失数据。
- 原 `wao-game` 的 2048 由现有服务端权威“数字熔炉”覆盖；记忆翻牌并入 WAO 原生游戏厅，服务端牌局与最佳步数写入成员的通用 `game_save`，不再维护 `/game/` 独立前端。
- `wao-insight` 不导入。其数据和操作均依赖未实现的 `/api/insight/**`，演示数据不进入真实业务入口；默认导航不再兜底显示该应用。未来具备真实聊天分析数据源和持久化 API 后，以 WAO 原生模块重新立项。
- Agent 原型中的定时假运行和 Demo 草稿工作台不导入产品入口，也不保留 `/api/agent/demo/**`；保留真实热点、账号定位、AI 成稿、素材、授权与发布链路。
- 外部应用注册表继续服务真正需要 iframe 的运行模块或第三方站点，但不再作为拆仓依据。

## 后果

- 一条需求只需在一个仓库、一个 PR 和一次 main 部署中交付，Agent 前后端契约、Gateway 路由与云静态文件不会再分批上线。
- 主发布耗时和构建上下文增加；换来配置、回滚、健康检查和飞书通知的统一。
- Agent Server 的故障仍由 iframe 健康状态隔离，WAO 主服务不因媒体处理依赖而同进程崩溃。
- 旧独立仓库在统一发布稳定后可归档为只读历史；归档不属于本 ADR 的自动代码变更，避免在验证完成前失去回退来源。
