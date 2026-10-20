# 接口入口

业务接口位于 `wao-server/src/main/java/cn/wayuwa/wao/controller/`，响应统一为 `ApiResponse<T>`。开发环境 Swagger UI 为 `http://127.0.0.1:8080/swagger-ui.html`，OpenAPI 为 `/v3/api-docs`；生产默认关闭。

工作区 MCP 使用 `/api/mcp/workspace` 与用户自己的令牌，按令牌已有能力授权，升级不能扩大旧令牌权限。契约见 [工作区 MCP](specs/wao-workspace-mcp.md)。

CLI 位于 `wao-cli/`，能力说明见 [CLI](specs/wao-cli.md)。新增接口时同步检查 MCP / CLI 的适用范围，不默认开放内部部署或数据库管理操作。


知识库文档支持拖入本机文件：图片保持内联，其他文件复用附件上传与卡片查看、下载能力；失败项可重试。附件随富文本文档保存，上传限制和文件访问权限沿用现有接口。


## 知识库独立表格与画板（2026-10-20）

`POST /api/docs` 可传 `pageType: document | spreadsheet | drawing`（默认 document）。表格和画板仅能在普通知识库创建。详情和轻量目录均返回 `pageType`，画板还返回 `drawingId`。创建画板在同一事务建立文档和草图关联，失败回滚。

表格通过原文档 PATCH、expectedRev 和本机草稿机制保存；正文使用矩形 table/tableRow/tableCell/paragraph/text，最多 100 行、26 列、1000 格。支持增删行列、键盘导航、Excel TSV 多格粘贴与整表复制。当前按文本保存（含数值和公式原文），不计算公式，不支持 xlsx 导入导出、合并格或多工作表。旧客户端只提交 blocks 时不能覆写表格正文。

画板复用 `/api/drawings` 的场景、版本和乐观锁。草图接口额外返回 `kbId/docId`，知识库和草图打开同一个 drawingId。权限沿用原草图创建者账号隔离，知识库页面本身可见不自动获得画板访问权。命名、归档和回收站由知识库管理；草图列表提供返回知识库入口。归档从草图列表隐藏但可经知识库打开，回收站中不可读；恢复后重新出现，文档最终清理通过外键同步清理画板与版本。

页面入口复用 ResourceMenu 和 ContextActionMenu，位于目录加号、目录行右键及目录空白处右键。小记不显示表格和画板创建选项。

工作区 MCP/CLI 沿用既有文档正文接口与令牌范围，本次未增加草图授权或新的命令。独立表格/画板创建入口为浏览器与 HTTP API，不将画板权限赋予旧令牌。
