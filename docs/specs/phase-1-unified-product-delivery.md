# Phase 1：WAO 多合一仓库与统一交付

决策见 [ADR-049](../adr/049-unified-product-monorepo-delivery.md)。

## 目标

维护者只进入 `wayuwacn/wao` 就能修改、测试和发布 WAO 主站、自媒体 Agent 与游戏能力；main 上的一次提交完成全部有效能力的构建、双节点部署、云前端更新、线上健康检查和一次飞书结果通知。

## 用户路径

1. 登录 WAO 后仍从同一侧栏进入自媒体 Agent，无二次登录；选题中心和今日创作只读取真实后端状态。
2. Agent 未配置模型、平台凭据或某平台没有官方发布接口时，页面明确说明不可用或只能导出，不生成虚假成功结果。
3. 从“游戏厅”顶部在数字熔炉和记忆翻牌之间切换；两个游戏都使用当前 WAO 成员身份，并由服务端保存完整棋盘、校验操作和计算成绩。
4. 群聊洞察没有真实 API 时不显示默认入口，不加载演示看板。

## 工程与部署要求

- 根 Maven reactor 必须包含 `agent-server`，容器构建必须从同一提交生成三个 Java 运行镜像。
- `agent-web` 与 `wao-web` 在同一次 CD 中构建；云端分别原子替换 `/var/www/wao-agent/dist` 与 `/var/www/wao/dist`。
- 主、从 Compose 都托管 `wao-agent-server`，网关等待 WAO Server 与 Agent Server 健康后再完成发布。
- Agent 媒体卷沿用旧 Compose 的显式卷名；首次切换先保留旧镜像作为回滚，再精确移除旧容器，避免 8081 冲突。共享 Session、`agent_` 业务表和独立 Flyway 历史必须兼容已有线上数据。
- 旧 Agent 私有配置只通过节点私有文件迁移，日志、提交和发布包不得包含实际值。
- 统一发布必须清理 `wao-game.conf`、`wao-insight.conf` 及其旧静态包；失败回滚 Nginx 时恢复原片段。旧 `/game/**` 跳转 `/games`，`/insight/**` 返回 410。
- 发布失败时三个应用镜像一起回滚；线上验收必须同时检查 `/api/hello`、`/api/agent/health`、`/agent/` 和主站入口。

## 验收标准

- `mvn test` 覆盖 WAO、Gateway 和 Agent；两个前端均通过单测、类型检查和生产构建。
- Agent 页面不再暴露 `/api/agent/demo/**` 对应的模拟运行与草稿工作台。
- 记忆翻牌加载、保存中、已保存、失败重试、空最佳记录和完成状态都有明确反馈；旧 revision 返回 409 后前端重新读取权威牌局。
- main CD 成功后，Agent 和 WAO 的制品来自同一提交且飞书保持静默；失败时只发送原因和 Jenkins 详情入口（见 [ADR-152：成功静默通知](../adr/152-silent-success-delivery-notifications.md)）。
- 不导入只有 fixtures 的 Insight；后续重新建设前必须先提供真实数据模型、接口和分析执行来源。
