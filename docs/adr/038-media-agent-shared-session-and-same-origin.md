# ADR-038：自媒体 Agent 改为同域部署并共享 WAO 会话

## 状态

已采纳，取代 ADR-037 中关于独立域名、一次性 SSO、独立用户库和 Python Worker 的决策；ADR-037 保留为历史记录。

## 上下文

独立仓库和独立发布仍是核心目标，但实际部署继续使用 WAO 现有三台服务器，前端也部署在现有云前端节点。用户进一步确认 Agent 必须复用 WAO 当前账号和 Session 体系，不接受进入 Agent 后形成第二套登录状态。

在相同主域下使用路径部署，可以直接复用 host-only 的 `WAO_SESSION`，省去跨域 iframe、一次性票据、登录映射和双会话注销一致性。首版业务仍处在 Demo 验证阶段，单个 Java 后端比 Java API 加 Python Worker 更易交付和运维。

## 决策

- 独立仓库固定为 `wayuwacn/wao-agent`，包含 Vue 前端、Java 后端、Agent migration、部署和 CI/CD。
- Agent 前端生产入口为 `https://wao.wayuwa.cn/agent/`，静态文件位于 `/var/www/wao-agent/dist`；WAO `/media-agent` 页使用同域 iframe 嵌入。
- Agent API 固定为 `/api/agent/**`。两台 WAO Gateway 在通用 `/api/**` 规则之前把该路径转发到本机 `wao-agent-server:8081`。
- Agent Server 使用与 WAO 相同的 MySQL schema、Spring Session JDBC 表、Cookie 名 `WAO_SESSION` 和 Session 属性 `wao.accountId`，只读 `account/member` 获取身份。
- Agent 不建立第二套密码、登录会话或本地用户映射。业务工作区与权限表使用 `agent_` 前缀，并通过 accountId 关联 WAO 账号。
- Agent migration 使用独立历史表 `flyway_agent_schema_history`，不得修改 WAO migration；Agent 业务表也不得使用无前缀名称。
- 首版后端为单个 Spring Boot 3.2.4 / Java 17 应用，持久化任务由应用内调度器推进；达到明确的吞吐、隔离或团队边界后再用 ADR 拆 Worker 或微服务。
- Agent CI/CD 独立更新 Linux 主节点和 Windows/WSL2 从节点的 Agent 容器，并更新云节点 Agent 静态目录，不构建、重启或部署 WAO。

## 后果

- 日常 Agent 前后端迭代不再要求 WAO 随同打包部署。
- 用户在 WAO 与 Agent 之间无需二次登录，WAO 会话失效时 Agent 同时失效。
- Agent 与 WAO 在账号表、Session 属性和数据库可用性上形成明确耦合；这些共享契约变化必须做兼容发布。
- 两个仓库对同一 schema 有写权限，必须依靠表前缀、独立 Flyway 历史和备份一致性防止冲突。
- 首次仍需发布一次 WAO，以加入菜单、Nginx 静态规则和 Gateway 路由；此后仅集成契约变化需要主站发布。
