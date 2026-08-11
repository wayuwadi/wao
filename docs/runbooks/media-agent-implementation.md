# 自媒体自动化 Agent 执行清单

> 独立仓库上线清单已结束。当前统一交付步骤见 [ADR-049](../adr/049-unified-product-monorepo-delivery.md)；以下内容仅用于追溯旧部署来源，不再作为执行指令。

## 已确定基线

- [x] 仓库：`wayuwacn/wao-agent`。
- [x] 技术：Vue 3 + TypeScript 前端，Java 17 + Spring Boot 3.2.4 单体后端。
- [x] 部署：现有 Linux 主节点、Windows/WSL2 从节点和云前端节点。
- [x] 入口：同域 `/agent/`，API `/api/agent/**`。
- [x] 身份：共用 `WAO_SESSION`、Spring Session JDBC 和 WAO account/member。
- [x] 数据：共用 schema，Agent 表 `agent_` 前缀，独立 Flyway 历史。

## 首次工程接入

### Agent 仓库

- [x] 建立 `agent-web`、`agent-server`、`deploy` 和 `docs`。
- [x] 建立本地 H2 纵向环境、共享账号模拟和明确的 Demo 标识。
- [x] 实现 Session、Dashboard、草稿乐观锁、运行与人工审核 API。
- [x] 建立后端集成测试、前端单元测试和生产构建。
- [x] 建立两台 Agent Server 与云端 Agent Web 的独立 CI/CD、健康检查和自动回滚。
- [ ] 在新仓库配置与 WAO 同名的 SSH/主机 Secrets 和 `DEPLOY_ENABLED=true`。

### WAO 仓库

- [x] 新增“自媒体 Agent”菜单、路由、可关闭页签和同域 iframe 容器。
- [x] 覆盖探测、加载中、失败重试和新窗口状态。
- [x] Gateway 在 `/api/**` 之前分流 `/api/agent/**` 到本机 Agent Server。
- [x] Nginx 从独立 `/var/www/wao-agent/dist` 提供 `/agent/`，不混入 WAO dist。
- [x] 两台 Gateway 配置 Agent 上游，不把 Agent 打进 WAO 后端镜像。

## 首次上线顺序

1. 合并并部署 WAO 集成 PR，使 Nginx 和 Gateway 认识 Agent 路径；此时菜单可能显示 Agent 未就绪，但主站其他功能不受影响。
2. 为 `wao-agent` 仓库配置部署变量与 Secrets。
3. 合并 `wao-agent` PR，独立流水线部署 Agent 两个后端和一个前端。
4. 浏览器登录 WAO，完成菜单进入、免二次登录、草稿保存、运行推进、人工审核和新窗口验收。
5. 之后 Agent 普通业务迭代只在 `wao-agent` 仓库交付。

## 后续业务阶段

1. 根据 Design 原型替换 Demo 页面，保持 API 与状态体验约束。
2. 引入内容版本、素材、模型连接与调用成本控制。
3. 确认首个拥有正式 API 权限的平台，先实现授权与结构化导出。
4. 实现真实发布前必须补充幂等键、审计、失败恢复与人工审核。
5. 只有出现明确的隔离或吞吐需求时，才通过新 ADR 拆 Worker、队列或微服务。
