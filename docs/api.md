# 接口入口

业务接口位于 `wao-server/src/main/java/cn/wayuwa/wao/controller/`，响应统一为 `ApiResponse<T>`。开发环境 Swagger UI 为 `http://127.0.0.1:8080/swagger-ui.html`，OpenAPI 为 `/v3/api-docs`；生产默认关闭。

工作区 MCP 使用 `/api/mcp/workspace` 与用户自己的令牌，按令牌已有能力授权，升级不能扩大旧令牌权限。契约见 [工作区 MCP](specs/wao-workspace-mcp.md)。

CLI 位于 `wao-cli/`，能力说明见 [CLI](specs/wao-cli.md)。新增接口时同步检查 MCP / CLI 的适用范围，不默认开放内部部署或数据库管理操作。
