# 接口入口

业务接口位于 `wao-server/src/main/java/cn/wayuwa/wao/controller/`，响应统一为 `ApiResponse<T>`。开发环境 Swagger UI 为 `http://127.0.0.1:8080/swagger-ui.html`，OpenAPI 为 `/v3/api-docs`；生产默认关闭。

工作区 MCP 使用 `/api/mcp/workspace` 与用户自己的令牌，按令牌已有能力授权，升级不能扩大旧令牌权限。契约见 [工作区 MCP](specs/wao-workspace-mcp.md)。

CLI 位于 `wao-cli/`，能力说明见 [CLI](specs/wao-cli.md)。新增接口时同步检查 MCP / CLI 的适用范围，不默认开放内部部署或数据库管理操作。


知识库文档支持拖入本机文件：图片保持内联，其他文件复用附件上传与卡片查看、下载能力；失败项可重试。附件随富文本文档保存，上传限制和文件访问权限沿用现有接口。


## 知识库独立表格与画板（2026-10-20）

`POST /api/docs` 可传 `pageType: document | spreadsheet | drawing`（默认 document）。表格和画板仅能在普通知识库创建。详情和轻量目录均返回 `pageType`，画板还返回 `drawingId`。创建画板在同一事务建立文档和草图关联，失败回滚。

表格通过原文档 PATCH、expectedRev 和本机草稿机制保存。独立页面采用 Univer 1.1.0，提供整页网格、工具栏、公式栏、单元格格式、合并、排序筛选和多 Sheet。正文为 `document: { type: "waoWorkbook", attrs: { snapshot: <IWorkbookData> } }`，完整保留公式、样式、合并区域与所有工作表；markdown 仅为有界、转义后的搜索和只读分享文本。默认 1000 行、26 列；服务端限制每工作簿 20 个 Sheet、每 Sheet 100000 行/1024 列、累计 100000 个单元格条目及快照 500 万字符。旧 table 正文打开时转换为工作簿，原有公式字符串仍保持文本。旧客户端只提交 blocks 时不能覆写表格正文；普通文档及模板不接受工作簿节点。表格顶部提供 Excel 导出：先提交当前单元格，下载整个工作簿的 .xlsx，保留数值、文本、常规公式、合并、常用单元格格式及行列尺寸。暂不提供 xlsx 导入；图形、批注、条件格式及富文本局部样式不在当前导出范围，富文本内容按纯文本保留。单击选中单元格后可直接键入，Enter 提交、Esc 取消；保留 Univer 工作簿焦点注册以确保直接输入进入单元格编辑与自动保存链路。

画板复用 `/api/drawings` 的场景、版本和乐观锁。草图接口额外返回 `kbId/docId`，知识库和草图打开同一个 drawingId。权限沿用原草图创建者账号隔离，知识库页面本身可见不自动获得画板访问权。命名、归档和回收站由知识库管理；草图列表提供返回知识库入口。归档从草图列表隐藏但可经知识库打开，回收站中不可读；恢复后重新出现，文档最终清理通过外键同步清理画板与版本。

页面入口复用 ResourceMenu 和 ContextActionMenu，位于目录加号、目录行右键及目录空白处右键。小记不显示表格和画板创建选项。

工作区 MCP/CLI 沿用既有文档正文接口与令牌范围，本次未增加草图授权或新的命令。独立表格/画板创建入口为浏览器与 HTTP API，不将画板权限赋予旧令牌。草图浏览器的串行保存、账号草稿隔离、失败恢复与文件管理行为见 [草图工作区](specs/drawings.md)，接口与令牌范围保持不变。


### 清单标题、截止日期与知识归档（2026-10-28）

清单主任务标题/截止日期沿用任务 title/due；内嵌子事项沿用 text/due，使用既有任务版本与草稿保存。浏览器新建未指定截止日期时默认上海当天，执行时段独立保留；HTTP/MCP/CLI 请求字段契约不变，不自动扩大令牌权限。

文档关联沿用任务或子事项 docs ID 列表。归档复用 POST /api/docs，提交 kbId、folderId、title、content（version 1 的 Markdown）和兼容 blocks，成功后将文档 ID 写回任务。原详情保留；文档创建与任务关联是两个请求，已收到的创建回执用于关联失败重试，不代表服务端跨请求事务或创建请求幂等。

## 小记图文整理（2026-10-28）

`GET /api/docs/notes/{docId}/visual-recap` 返回本人小记最近整理；`POST` 接收 `expectedRev` 和 32 位小写十六进制 `requestId`。返回 `status`（EMPTY/RUNNING/SUCCEEDED/FAILED），已创建结果带 `sourceRev/sourceText/stale/id`，成功带 `html`，失败带 `message`。响应为 ApiResponse，禁止缓存。

复用本机 AI 队列；来源版本不一致为 409，非本人或删除/归档来源为 404；正文限制 24000 字符。HTML 仅作为私有 JSON 数据返回，浏览器必须经白名单和沙箱渲染。只读结果不覆盖正文。此能力当前限浏览器，MCP 与 CLI 不新增权限或命令。流程与保留规则见 [小记图文整理](specs/notes-visual-recap-2026-10-28.md)。

## 文档限时分享（2026-10-28）

知识库文档与小记共用作者管理的 `GET/POST /api/docs/{id}/public-shares` 和 `DELETE /api/docs/{id}/public-shares/{shareId}`。POST 使用 `{ "durationHours": 1 | 24 | 168 }`，返回随机 token 一次；匿名读取只接受 `/api/share/notes/{token}`、`{token}.md` 与快照内图片。旧 `/api/share/docs/{id}[.md]` 一律失效，不能凭文档 ID 免登录读取。完整权限、快照与迁移边界见 [文档限时分享](specs/document-share-tokens-2026-10-28.md)。

## 任务标签与个人收集箱（2026-10-29）

`GET /api/task-labels` 返回当前成员可见任务及有效子任务的标签记录 `{taskId, subId, revision, tags}`。`PUT /api/task-labels/{taskId}` 接收 `{subId, revision, tags}`，空 subId 表示主任务；需登录并有源任务可见权限，锁后复核。版本冲突返回 409，相同结果重试幂等；tags 空数组明确清空。每项最多 20 个标签，名称 1–40 字符且无控制字符。标签独立保存，普通任务 PUT 不修改标签。

个人收集箱使用 task.project 空串，只有 owner 可见；列表、详情及锁后写入均校验。浏览器待办提供任务/子任务标签筛选与成员级象限配置。详见 [任务工作台规范](specs/task-workspace-labels-2026-10-29.md)。
# 番茄专注

浏览器会话接口（按当前成员隔离）：`GET /api/focus` 返回服务端时间、当前计时、最近 200 条历史与今日/累计统计；`PUT /api/focus/{id}` 开始，id 为 32 位小写十六进制稳定重试编号，body 为 `{mode: pomodoro|stopwatch|break, minutes: 1..180, taskId, subId, note}`；`POST /api/focus/{id}` 操作，body 为 `{revision, action: pause|resume|save|finish, note}`。省略 note 视为空笔记，客户端每次携带当前草稿。

开始时检查关联任务权限，返回自身已存在的同 id 记录，已有其它进行中记录返回 409。修改版本过期返回 409；完成事务同步创建带「番茄钟」标签的小记，重复完成返回同一结果。休息不创建小记。详情见 [番茄专注规范](specs/pomodoro-focus.md)。不增加 CLI/MCP 或工作区令牌授权。
# 倒数日与习惯打卡

浏览器登录成员接口（不自动扩展已有工作区令牌）：

- `GET /api/routines?month=2026-10`：成员的日期/习惯、日期计算、统计及所选月份/最近 7 天的日志。`today` 以服务端上海日期为准。
- `PUT /api/routines/{id}`：保存条目，`id` 为稳定 32 位十六进制编号，新增 `revision=0`，修改携带当前版本。`kind=countdown|habit` 创建后不可更改。`status=active|archived|trash` 支持可恢复删除。
- `PUT /api/routines/{id}/checkins/{YYYY-MM-DD}`：保存本人习惯当日的 `{revision, amount, note}`，新日志版本 0，数量 0 表示撤销。响应含历史 `target` 快照。
- `PUT /api/focus/{id}` 可增加可选 `habitId`，不能同时关联任务；结束小记包含习惯链接，不自动打卡。

跨成员返回不存在，过期版本返回 409；详情与日期/连续统计定义见 [倒数日与习惯打卡](specs/countdowns-habits.md)。

## Moka 考勤连接恢复（2026-10-29）

`POST /api/attendance/sync?from=YYYY-MM-DD&to=YYYY-MM-DD` 增加可选 `confirmSessionReplacement`，默认 `false`。仅当前成员在浏览器明确确认可能使其他设备上的 Moka 登录失效后，本次手动请求才传 `true`。服务端先普通登录，遇到 Moka 的 `step=4` 时使用其返回的 `rid` 确认一次；授权不落库，不传给后续重登、定时同步或历史回填。验证码仍须本人在 Moka 处理。

连接状态新增 `NEEDS_CONFIRMATION`，表示等待用户确认接管已有会话；该状态与 `NEEDS_LOGIN` 都暂停自动登录。浏览器兼容旧版保存的“其他会话”错误，重试时给出明确确认。不会新增 CLI/MCP 能力或扩大工作区令牌权限。

### 任务变更通知

`GET /api/task-changes`：使用现有浏览器会话认证，返回 `text/event-stream`，仅包含 `ready`、`changed`、`heartbeat` 事件，data 为 `refresh`，不传输任务内容或标识。任务／标签成功写入后向同一成员的连接发送 `changed`。前端重新请求已有授权列表，重连时补读；一分钟连接超时后重新验证登录状态，最多保留 12 个同成员连接。未登录返回 401。该通知接口不开放给既有工作区 MCP 或 CLI 令牌，现有写接口和版本冲突协议保持不变。
