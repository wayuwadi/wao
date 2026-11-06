# 接口入口

## 资源库（2026-11-06）

`GET /api/files` 和 `GET /api/images` 元数据新增只读 `byteSize`，以字节表示原文件的准确大小；图片同时返回 `contentType`、`createdAt` 和 `previewUrl`，原图仍使用 `url`。`GET /api/hosted-pages` 新增可排序的 `updatedAtIso` 和编辑版本 `revision`。字段增量兼容旧客户端；格式化的 `size/time/updatedAt` 保留。资源列表在数据库查询中限定已授权项目范围，再执行来源文档权限过滤，不载入二进制正文。

`PATCH /api/files/{id}` 接收 `{displayName, expectedDisplayName?}`。浏览器改名提交编辑开始时的原始 `displayName`：显式 `null` 匹配未设置名称，字符串按精确字节比较。名字已被其他客户端修改时返回 409，草稿保留并可读取最新名称；重复保存相同目标名称幂等。省略 `expectedDisplayName` 保持已有接口行为，删除的文件返回 404。

`PUT /api/hosted-pages/{id}` 和 `PUT /api/hosted-pages/{id}/content` 的 multipart 新增可选 `expectedRevision`，取编辑开始时读取的非负整数版本；SQL 在写入时原子比较版本，过时返回 HTTP 409 / `code: 40900`，已删除返回 404。省略字段兼容旧调用。新建和迁移前已有页面版本为 0，每次编辑、正文替换及 AI 报告写入均原子加 1；列表和写入响应携带当前版本。只替换正文保留最新标题、后缀、图标和归属；未上传 HTML/icon 的编辑也不会写回旧正文。时间字段不承担并发校验。V208 仅新增 `hosted_page.revision BIGINT NOT NULL DEFAULT 0`，保留已有页面和正文。

`GET /api/images/{id}/preview` 对 PNG/JPEG/BMP 按需生成最长边 768px 的派生图，保留透明和 EXIF 方向；原文件不改写。进程缓存最多 24MiB、256 项，单项不超过 1MiB；命中缓存前仍验证访问权限和记录存在。GIF/WebP/SVG 保持原格式；未知或损坏的历史图片沿用原始读取兜底。私有图片及预览响应保持 `private, no-store`，公开图片沿用一小时缓存。图片正文、公开小记图片别名和托管网页图标响应通过 CSP 沙箱禁止主动脚本和外部资源加载。

浏览器新增分页、名称/来源检索、文件类型过滤、稳定排序与容量汇总；按账号和页签保留浏览条件。上传队列保留原项目归属，跨页继续、显示传输进度并等待服务端保存确认，成功后释放本机文件对象。旧客户端缺少 `byteSize` 时容量显示估算值。详细行为及验证入口见 [资源库规范](specs/resource-library.md)。本次不增加 HTTP 写入权限、工作区 MCP 或 CLI 命令，也不扩大既有令牌范围。

## 智能清单显示偏好（2026-11-06）

`GET /api/preferences/smart-list/items` 返回当前成员的清单显示选择（缺省为显示）。`PUT /api/preferences/smart-list/items/{projectId}` 接收 `{visible, expected}`，`expected` 为上次读取的空字符串、`0` 或 `1`。每个清单独立保存，存在并发修改时返回 409；重复提交已经保存的值是幂等的。偏好只影响待办工作台的所有、今天、明天和最近 7 天，不改变任务或清单的共享权限。

## 待办视图偏好与修改时间（2026-11-04）

浏览器使用已有的成员级 `GET/PUT /api/preferences/{key}` 保存待办显示偏好。键 `todo-display-v1` 记录日期、详情样式和视图同步开关；`todo-view-v1:{scope}` 按清单／筛选记录视图、分组、排序和显示开关。关闭视图同步时，视图调整仅写入浏览器。

任务响应新增只读、可空的 `updatedAt`（ISO 日期时间）。V203 记录迁移后的实际修改；尚未修改的任务按已有 `createdAt` 排序，两个时间都未知时排在最后。内嵌子事项使用所属任务的修改时间。更新请求中的 `updatedAt` 不参与保存与版本校验，工作区 MCP 与 CLI 不新增权限。

`POST /api/tasks` 的 `project` 可省略或为空，服务端统一写入空清单，并将负责人固定为当前成员。指定清单时继续检查该清单权限。浏览器新建默认展示收集箱，显式清单草稿继续恢复。

## 任务菜单操作（2026-11-03）

浏览器会话调用 `PUT /api/tasks/{taskId}/menu-operations/{operation}`，operation 为 UUID v4，正文为 `{accountId, change: {action, subId?, versions}}`。action 为 `duplicate | abandon | restore | note | task`；versions 包含受影响主任务及独立后代的编辑版本。服务端锁定项目任务、复核权限和版本，在同一事务写入整个分支与回执。响应 `{selectedId,tasks}`；相同账号和操作编号重试返回相同结果，变更请求内容返回 409。复制产生新任务/子任务编号，保留正文、附件引用和标签，重置完成状态及评论/活动历史。响应丢失时浏览器保存原请求重试，不能把旧操作改成相反动作。

`workItemType: note` 表示原清单中的笔记，沿用原任务 ID、权限和正文；没有子任务才可转换，日期清空，不参与日期视图、四象限和任务完成统计，可转换回任务。`status: 已放弃` 保留任务，在已放弃视图重新开始；批量放弃和恢复覆盖未完成分支，不重开原已完成后代。置顶属于成员偏好 `task-pins-v1`，不修改他人的排序。没有新增数据库迁移；操作回执复用既有事务回执表。MCP/CLI 不新增菜单写入能力或授权。

## 小记创建重试（2026-11-03）

`PUT /api/docs/notes/{id}` 仅创建，不再凭同作者、同 ID 覆盖已有小记。首次请求与正文同事务保存不可变请求摘要；相同请求重放返回当前正文、图片和版本，不回退 AI 结果，也不重复发送创建通知。不同请求复用 ID 返回既有的 400「小记文档 id 已被占用」；浏览器保留完整草稿、换新 ID 后最多自动重试一次。网络失败仍沿用原 ID。

升级前没有摘要的记录只接受与当前正文一致的重试；历史 `createOnly` 调用仍只返回已有记录。修改已有正文继续使用 `PATCH /api/docs/{id}` 和 `expectedRev`。MCP/CLI 的创建入口沿用相同保护，不增加权限。此修复不会自动恢复已被覆盖的数据，恢复应依据原稿或历史版本逐篇确认。

业务接口位于 `wao-server/src/main/java/cn/wayuwa/wao/controller/`，响应统一为 `ApiResponse<T>`。开发环境 Swagger UI 为 `http://127.0.0.1:8080/swagger-ui.html`，OpenAPI 为 `/v3/api-docs`；生产默认关闭。

工作区 MCP 使用 `/api/mcp/workspace` 与用户自己的令牌，按令牌已有能力授权，升级不能扩大旧令牌权限。契约见 [工作区 MCP](specs/wao-workspace-mcp.md)。

CLI 位于 `wao-cli/`，能力说明见 [CLI](specs/wao-cli.md)。新增接口时同步检查 MCP / CLI 的适用范围，不默认开放内部部署或数据库管理操作。


知识库文档支持拖入本机文件：图片保持内联，其他文件复用附件上传与卡片查看、下载能力；失败项可重试。附件随富文本文档保存，上传限制和文件访问权限沿用现有接口。


## 知识库独立表格与画板（2026-10-20）

`POST /api/docs` 可传 `pageType: document | spreadsheet | drawing`（默认 document）。表格和画板仅能在普通知识库创建。详情和轻量目录均返回 `pageType`，画板还返回 `drawingId`。创建画板在同一事务建立文档和草图关联，失败回滚。

表格通过原文档 PATCH、expectedRev 和本机草稿机制保存。独立页面采用 Univer 1.1.0，提供整页网格、工具栏、公式栏、单元格格式、合并、排序筛选和多 Sheet。正文为 `document: { type: "waoWorkbook", attrs: { snapshot: <IWorkbookData> } }`，完整保留公式、样式、合并区域与所有工作表；markdown 仅为有界、转义后的搜索和只读分享文本。默认 1000 行、26 列；服务端限制每工作簿 20 个 Sheet、每 Sheet 100000 行/1024 列、累计 100000 个单元格条目及快照 500 万字符。旧 table 正文打开时转换为工作簿，原有公式字符串仍保持文本。旧客户端只提交 blocks 时不能覆写表格正文；普通文档及模板不接受工作簿节点。表格顶部提供 Excel 导出：先提交当前单元格，下载整个工作簿的 .xlsx，保留数值、文本、常规公式、合并、常用单元格格式及行列尺寸。暂不提供 xlsx 导入；图形、批注、条件格式及富文本局部样式不在当前导出范围，富文本内容按纯文本保留。单击选中单元格后可直接键入，Enter 提交、Esc 取消；保留 Univer 工作簿焦点注册以确保直接输入进入单元格编辑与自动保存链路。

画板复用 `/api/drawings` 的场景、版本和乐观锁。草图接口额外返回 `kbId/docId`，知识库和草图打开同一个 drawingId。个人草图按创建者隔离，关联画板沿用知识库所有者或成员可编辑权限。草图页可编辑关联画板并通过 PATCH /api/drawings/{id} 改名，同一事务更新知识库文档标题与修订和草图元数据，不改动文档正文或画布内容。归档和回收站仍由知识库管理；草图顶部提供返回知识库入口。归档从草图列表隐藏但可经知识库打开，回收站中不可读；恢复后重新出现，文档最终清理通过外键同步清理画板与版本。

草图分组按当前成员隔离：`GET /api/drawings/groups` 返回分组和文件归属，`POST /api/drawings/groups` 新建，`PATCH /api/drawings/groups/{id}` 重命名，`PUT /api/drawings/{id}/group` 移动或传空值移回未分组。文件和目标分组分别校验当前成员权限。`POST /api/drawings/{id}/copy` 原子复制可访问的已保存画布到当前成员的个人草图，保留当前成员的分组，副本不关联原知识库文档。 `POST /api/drawings` 可选传 `groupId`，在同一事务校验成员分组并创建归属。分组列表返回 `order` 和 `revision`；`PUT /api/drawings/order` 使用 `order`（`g:` 分组、`f:` 草图键）及 `revision` 比较版本后保存手动排序，过期返回冲突，文件与分组均校验可访问范围。

页面入口复用 ResourceMenu 和 ContextActionMenu，位于目录加号、目录行右键及目录空白处右键。小记不显示表格和画板创建选项。

工作区 MCP/CLI 沿用既有文档正文接口与令牌范围，本次未增加草图授权或新的命令。独立表格/画板创建入口为浏览器与 HTTP API，不将画板权限赋予旧令牌。草图浏览器的串行保存、账号草稿隔离、失败恢复与文件管理行为见 [草图工作区](specs/drawings.md)，接口与令牌范围保持不变。


### 清单标题、截止日期与知识归档（2026-10-28）

清单主任务标题/截止日期沿用任务 title/due；内嵌子事项沿用 text/due，使用既有任务版本与草稿保存。待办快速新增默认无截止日期；月历「清除」通过既有保存接口写入空字符串 due，执行日期与时段独立保留。「今天」同时显示本人未完成的逾期任务及子事项，不改写原截止日期。日期变更保存失败时保留当前项与重试入口。HTTP/MCP/CLI 请求字段契约不变，不自动扩大令牌权限。

文档关联沿用任务或子事项 docs ID 列表。归档复用 POST /api/docs，提交 kbId、folderId、title、content（version 1 的 Markdown）和兼容 blocks，成功后将文档 ID 写回任务。原详情保留；文档创建与任务关联是两个请求，已收到的创建回执用于关联失败重试，不代表服务端跨请求事务或创建请求幂等。

## 小记图文整理（2026-10-28）

小记保存后直接在当前清单顶部展示已保存条目，保留原筛选条件；不再展示额外的「已保存到服务器／查看刚保存的小记」提示条。卡片标题同行提供保存状态、字数、阅读、编辑和 AI 工具，窄屏允许换行。目录刷新保留请求期间已保存的新条目，不允许低版本响应覆盖新正文；后续刷新仍正常反映删除。

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

日历的「19:30 前下班」额度按显示月份的整月工作日数 × 20% 向下取整，保证 19:30 出勤率至少 80%。工作日沿用个人应工作日安排，并扣除中国法定放假、计入调休上班；请假、缺卡和未来日期不缩减月度基数。19:30 整及次日下班不占提前下班额度。切换日／周视图仍读取整月节假日；节假日读取失败时暂不显示额度。

`POST /api/attendance/sync?from=YYYY-MM-DD&to=YYYY-MM-DD` 增加可选 `confirmSessionReplacement`，默认 `false`。仅当前成员在浏览器明确确认可能使其他设备上的 Moka 登录失效后，本次手动请求才传 `true`。服务端先普通登录，遇到 Moka 的 `step=4` 时使用其返回的 `rid` 确认一次；授权不落库，不传给后续重登、定时同步或历史回填。验证码仍须本人在 Moka 处理。

连接状态新增 `NEEDS_CONFIRMATION`，表示等待用户确认接管已有会话；该状态与 `NEEDS_LOGIN` 都暂停自动登录。浏览器兼容旧版保存的“其他会话”错误，重试时给出明确确认。不会新增 CLI/MCP 能力或扩大工作区令牌权限。

### 请假明细与工时视图（2026-10-31）

考勤查询和单日保存响应的 `days[]` / `Day` 新增 `leaves` 数组，元素包含 `title`（假别）、`startAt` / `endAt`（本地日期时间）、`duration`（可空数值）和 `durationUnit`（可空原始单位）。读取 Moka `leaveRecord` 或命名空间 `#leaveRecord` 的 `fieldValue`，只保存与该日相交的有效时段；空字段包装不表示请假。时长使用 Moka 原值，跨日申请保留整段时长，不把含午休的起止时间差当作请假小时数。无明细返回空数组；不保存审批 ID 或请假原因。

V200 为远端明细新增独立可空列。远端同步替换明细，包括清空已撤销记录，但保留手工覆盖；手工覆盖生效时响应不显示远端明细，恢复 Moka 原值后重新显示。请假继续排除工时日均；部分请假有真实打卡时保留打卡展示。Moka 错误码 `100007` 按会话失效进入既有恢复流程，不绕过单次接管确认。

月历把工时和请假放在日期旁，缺卡和源端异常采用整格浅色及文字提示；缺卡仅对已结束的应出勤日期判断，扣除法定休息日，不把今天尚未下班标为缺卡。侧栏每周均值合并为一行，趋势可切换每日工时与周均，点击或键盘聚焦数据点查看数值。请假、缺卡、无有效出勤周保留空档，不作为零值参与趋势。年／周／日视图继续保留工时事件。

### 任务变更通知

`GET /api/task-changes`：使用现有浏览器会话认证，返回 `text/event-stream`，仅包含 `ready`、`changed`、`heartbeat` 事件，data 为 `refresh`，不传输任务内容或标识。任务／标签成功写入后向同一成员的连接发送 `changed`。前端重新请求已有授权列表，重连时补读；一分钟连接超时后重新验证登录状态，最多保留 12 个同成员连接。未登录返回 401。该通知接口不开放给既有工作区 MCP 或 CLI 令牌，现有写接口和版本冲突协议保持不变。

## 个人任务模板（2026-11-06）

`GET /api/task-templates` 返回当前登录成员自己的模板；`PUT /api/task-templates/{id}` 用 32 位小写十六进制编号保存 `{accountId,content}`，`content` 包含 `{name,title,description,steps}`，其中步骤为 `{key,parentKey,title,description}`。模板最多 500 个子项，完整内容最多 100 万字符；服务端校验标题、长度、重复编号和父子循环。相同编号与相同内容重试幂等，不同内容返回冲突。`DELETE /api/task-templates/{id}` 使用 `X-WAO-Account-Id` 指定发起账号。写入时复核当前会话与发起账号一致；删除内容后保留空的编号墓碑，避免迟到的保存重试恢复已删除模板；跨成员访问返回不存在。响应使用 `private, no-store`。

模板保留标题、描述和子任务层级，不携带原负责人、完成状态、日期、评论、标签或附件关联。`PUT /api/task-templates/creations/{taskId}` 接受 `{accountId,templateId,projectId}`，在同一事务中创建任务和内嵌子任务并保存操作回执。相同编号及参数的重试只读取当前任务，不覆盖之后的修改；任务已删除时不重新创建，参数或账号不匹配时拒绝。本机保存待确认的创建编号和参数。新增 V206，不向工作区 MCP 或 CLI 令牌增加能力。

待办详情侧栏的评论随任务版本和写入回执一起保存，失败重试不会重复追加，侧栏不发送提及通知。既有评论接口仍供原任务抽屉使用：新增、修改和删除在事务内锁定任务并复核权限，只更新详情字段；旧新增接口没有请求级幂等编号，响应丢失时不能保证重复提交只创建一次。


## 任务重复与站内提醒（2026-11-06）

浏览器会话通过 `/api/task-time/{id}` GET/PUT 读取及 CAS 保存规则；`POST /skip` 使用 `{revision,editVersion}` 显式跳过；`POST /retry` 重试已保存规则；`POST /test/{uuid}` 由本人显式试发私人站内提醒。上述动作均重新检查任务可见性与当前负责人，非负责人 GET 不返回个人规则。响应含 `schedulerEnabled`，两项调度开关均关闭时明确拒绝启用自动提醒。

独立 V207 存储避免旧客户端任务全文覆盖规则；下一次任务和回执同事务生成，提醒写入与按任务/成员/时间槽的去重回执同事务提交。MCP/CLI 不新增能力或扩大已有令牌授权。完整日期、重复、内嵌子项、取消与重试语义见 [任务时间规则](specs/task-time-rules-2026-11-06.md)。
