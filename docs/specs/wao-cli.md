# WAO CLI 规格与能力台账

2026-10-28：[文档限时分享](document-share-tokens-2026-10-28.md)沿用作者浏览器会话，不新增 CLI/MCP 权限。旧工作区令牌不能因 HTTP API 支持知识库文档而获得创建外链能力；有终端分享需求时先设计独立发布 scope 和审计。

2026-10-15：[思考图提示词](notes-thinking-prompts-2026-10-15.md)仅扩展浏览器成员私有 AI 设置与既有重做流程，CLI/MCP 不获得个人模型配置或自动生成权限，本次无新增命令；有独立 AI scope 后重审。

本文件描述当前契约；修改记录在 Git 历史中查看。

## 目标

在终端和脚本中使用 `wao` 操作已获授权的 WAO 业务能力，并让 Codex 等 AI 可通过标准命令调用。首版直接使用工作区 MCP；认证与权限由服务端设备令牌决定，CLI 不绕过业务服务，也不因为新增 HTTP API 就自动开放新能力。

CLI 支持通过独立授权的现有托管页 API 发布一个公开 HTML 页面；命令、确认交互、文件校验与匿名内容核验见 [当前架构](../architecture.md)。此能力不映射为 MCP 工具，也不授予列表、修改或删除页面的权限。

普通文件业务上限调整为 200MB 只改变既有浏览器文件上传与系统管理员设置；工作区 MCP 令牌没有系统设置写权限，本次不新增 CLI 命令。未来需要脚本化配置时，先设计独立的管理员授权及审计。

小记阅读布局、成员级标题 / 配图 / 润色提示词、GPT 模型及思考深度选择只作用于浏览器中已保存小记的个人阅读增强流程；现有 MCP 没有批量笔记变更工具，也没有读取服务主机模型能力的权限，本次不新增 CLI 或扩展旧令牌权限。浏览器图片请求的幂等请求号只用于同一登录会话在网络重试后续接原任务，不是终端操作能力。未来若支持终端批量处理，需另行设计有界选择、文档版本条件与冲突确认。

小记 AI 阶段的并行 / 串行模式与并发数只控制当前成员在浏览器保存新小记时的自动整理调度；它不提供终端选择、批量处理或新的数据操作权限，因此不新增 `wao` 子命令或 MCP 工具。若以后允许从 CLI 批量发起小记 AI 任务，需重审成员并发配额、版本冲突和任务恢复语义。

小记智能标签推荐同样是人工保存后针对单篇正文、小记标签字典与当前文档版本的编辑步骤；自动执行已由服务端持久接续，关闭浏览器后仍运行。`NOTE_TAGS` 不构成独立的终端批量操作，因此不新增 CLI 子命令或工作区 MCP 工具。服务端只读任务按原允许清单过滤，写回再次锁定当前字典，不创建新标签。详见 notes-server-existing-tags.md。

项目列表的个人分组与排序只影响当前成员的浏览器展示，复用成员偏好接口且不改变共享项目字段。它不构成独立业务操作，CLI 和工作区 MCP 不开放通用成员偏好写入，也不新增分组命令；未来若出现脚本化布局需求，需先定义字段白名单、并发冲突与操作审计。

## 命令与契约

- Node.js 22+；仓库内 `wao-cli` 包通过 `npm link` 提供 `wao` 命令。默认远程地址为 `https://wao.wayuwa.cn/api/mcp/workspace`。
- 能力中心 → 我的连接 → CLI/MCP 新授权，选择需要的具体操作及项目 / 知识库范围后复制工作区令牌；交互登录接受 `wao_workspace_` 令牌或包含唯一该令牌的连接说明，验证后在 Windows 使用当前用户 DPAPI、在 macOS 使用系统钥匙串保存。记忆 MCP 令牌及没有工作区令牌的文本在发出网络请求前拒绝。`WAO_TOKEN` 供自动化或其他系统使用并覆盖本机安全凭据。CLI 不将令牌写入本地明文配置或日志，不能通过登录或升级 CLI 给已有令牌增加工具授权。
- `wao tools list` 使用实时 `tools/list`。`wao tools call NAME --input-file FILE` 提供未封装工具的通用入口；调用前按令牌可见工具校验。常用子命令使用同一 schema，字段以 `--kebab-case` 输入。
- `wao tools describe NAME` 和业务命令 `--help` 返回当前工具 schema；字段类型、枚举和必填条件来自服务端。`wao --version` 无需连接，`wao doctor` / `auth status` 检查连接并在可用时读取身份、权限及版本；身份工具未授权或旧服务器未提供时说明连接成功但身份不可查询，给出当前可用工具和能力中心授权入口，不假设当前成员或写权限，也不绕过快照探测未授权工具。
- `--output json` 输出 `structuredContent.data`，默认数组用表格、普通对象用 JSON、分页对象渲染 `items` 与总数量 / 偏移 / 下一页提示；所有错误进入 stderr 且退出非零。CLI 不自动重试写入。远程 HTTP 被拒绝，本机 HTTP 仅供测试。
- 正文写命令的 `--file FILE|-` 严格读 UTF-8，将 Markdown / 纯文本作为 `content={version:1,markdown,document:null}`，保留原分段、标题、列表等。与 JSON / 参数中的 `content`、`blocks`、`text` 互斥；两个输入源同时占 stdin 也明确拒绝。`--input-file` 专门读 JSON 参数，不能将文章正文误作为 JSON。
- `notes export` / `documents export` 支持 `--format md|json --file FILE|-`；Markdown 优先用高级正文，历史 blocks 转换相应 Markdown，JSON 保留完整文档版本。文件默认独占创建，不覆盖已有文件；只有导出接受无值布尔开关 `--force`。
- 小记创建和日小记默认生成 schema 已声明的稳定 `document_id`（无横线 UUID），日安排与日小记默认生成 `operation_id` UUID；允许显式传入标识，业务日期仍显式必填。写之前在独立本机目录 `WAO/cli/data/operations`（Windows LOCALAPPDATA，缺少则 APPDATA）或 `~/.local/share/wao-cli/operations` 落盘回执，同时保留操作号与文档号，stderr 展示标识与路径，`--receipt-file NEW_FILE` 可覆盖路径。已有回执拒绝再次提交；成功记录结果，错误保留 `unknown`。标识可通过 `daily operation` / `notes get` 查状态。CLI 不自动重试写入，不自动获取新 CAS 版本覆盖请求；回执没有凭据，但可能包含成员业务数据。
- `documents list` 发现实时 schema 支持 `offset` 后默认发送 `offset=0,limit=50`，避免目录超过默认页时不知被截断；显式 flags / JSON 可覆盖分页值。旧服务器没有 offset 则不注入，保持原数组契约。`tasks list --status open` 包括主任务已完成但子任务未收尾；跨任务移动快照使用双方 `subtaskVersion`，集合编辑使用任务 `editVersion`。
- CLI 不提供删除、直接通知、浏览器会话管理或令牌签发命令。令牌签发和吊销仍在 WAO 页面进行。

## 当前覆盖

音乐功能已按 ADR-300 下线，播放器与网易云专属 API 不再提供。CLI 与工作区 MCP 原本没有专属音乐能力，本次不新增替代命令；通用文件资产及其音视频流读取保留。

| 领域 | MCP 工具 | CLI 子命令 |
| --- | --- | --- |
| 项目读取 | `list_projects`、`get_project` | `projects list`、`projects get ID` |
| 工作区诊断与成员 | `get_workspace_info`、`list_members` | `workspace info`、`members list`；`auth status` / `doctor` 自动使用身份工具（旧服务端兼容） |
| 项目写入 | `create_project`、`update_project` | `projects create`、`projects update ID`；可选 `--due yyyy-MM-dd`，清空使用 `--input '{"due":""}'`，省略不改已有日期 |
| 任务读取 | `list_open_tasks`、`list_tasks`、`get_task` | `tasks open`、`tasks list`、`tasks get ID`；list 支持实时 schema 的条件与分页 |
| 子任务状态 | `update_subtask_status` | `subtasks status ID` |
| 任务写入 | `create_task`、`update_task` | `tasks create`、`tasks update ID` |
| 子任务编辑 | `create_subtask`、`update_subtask`、`reorder_subtasks`、`move_subtask`、`promote_subtask` | `subtasks create TASK_ID`、`subtasks update SUB_ID`、`subtasks reorder TASK_ID`、`subtasks move SUB_ID`、`subtasks promote SUB_ID --task-id TASK_ID --expected-source-version VERSION`；版本条件不自动替换，提升需独立授权 |
| 任务关系 | `move_task`、`link_task_document` | `tasks move ID`、`tasks document ID --mode add/remove` |
| 任务评论 | `add_task_comment`、`update_task_comment` | `tasks comment TASK_ID` / `comments add TASK_ID`、`comments update COMMENT_ID --task-id TASK_ID` |
| 知识库读取 | `list_knowledge_bases`、`get_knowledge_base` | `knowledge list`、`knowledge get ID` |
| 知识库写入 | `create_knowledge_base`、`update_knowledge_base`、`move_knowledge_node` | `knowledge create`、`knowledge update ID`、`knowledge move` |
| 文档读取 | `list_documents`、`get_document` | `documents list KB_ID`、`documents get ID` |
| 文档写入 | `create_document`、`update_document` | `documents create`、`documents update ID` |
| 文档归档 | `list_archived_documents`、`archive_document`、`unarchive_document` | `documents archived KB_ID`、`documents archive ID`、`documents restore ID`；写带 `expected_rev` |
| 文档导出 | `get_document` | `documents export ID`、`notes export ID`；md / json，仅本机文件写入 |
| 小记读取与创建 | `list_notes`、`create_note`、`get_document` / `update_document` | `notes list`、`notes create`、`notes get ID` / `notes update ID`；支持 UTF-8 正文文件 |
| 小记人工标签 | `list_note_tags`、`create_note_tag`、`update_note_tags` | `tags list/create` / `notes tags list/create`、`notes tags add/remove/set/clear ID`；mode 为 add / remove / replace / clear，版本必填 |
| 文档模板 | `list_doc_templates`、`create_doc_template`、`update_doc_template` | `templates list/create`、`templates update ID`；更新带 `expected_updated_at` |
| 工作区检索 | `search_workspace` | `search --keyword TEXT`；kinds 为 task / note / document，支持分页 |
| 日读取与排程 | `get_todo_day`、`list_todo_days`、`schedule_task` | `todo day DATE`、`todo days --from DATE --to DATE`、`tasks schedule ID`；需对应操作快照授权，due 与执行安排保持分离 |
| 原子日小记与回执 | `record_daily_note`、`get_daily_operation` | `daily record`、`daily operation OPERATION_ID`；操作标识在调用前保存 |
| 文件夹写入 | `create_folder`、`rename_folder` | `folders create`、`folders rename` |
| 托管页公开发布 | 独立托管页 API（非 MCP 工具） | `pages publish`（仅新建，不提供列表、修改、删除） |

## 后续能力同步

WeKnora 目标库允许同时启用 Wiki 与向量索引，只改变既有内容同步的目标库校验，不新增用户操作或授权边界。内容同步仍通过 WAO 登录会话和受限机器密钥执行；现有工作区 MCP 令牌没有同步配置与执行权限，故本次不新增 CLI 命令。若需要无人值守的终端同步，再设计专用权限与运行状态查询后重审。

账务本已按 ADR-295 全站下线；CLI 与工作区 MCP 均不提供账务本命令或工具。

新增或修改业务服务、HTTP API、MCP 工具时，在同一个需求中审视对应用户操作是否需要 CLI。需要时维护服务端工具目录或适当的认证接口、CLI 子命令、此台账及权限和端到端测试；暂不适合开放时，在相关规格或 ADR 中写明原因和以后触发条件。通用 `tools call` 让新增 MCP 工具可立即被显式调用，但不能代替常用命令的可发现性、帮助文案与测试。不得用“所有 API 自动映射”替代逐项权限设计。

## 子任务状态修改

用户明确要求开放独立子任务状态更新，新增 `update_subtask_status`，只向现有可读写令牌开放；每次调用继续检查成员项目可见性，只读令牌不可调用。`update_task` 仍仅修改主任务。

必填参数：`task_id`、`subtask_id`、`status`、`expected_subtask_version`。状态为未开始、进行中、待验证或已完成。先 `get_task` 读取 `subtaskVersion`，将其作为 expected_subtask_version。服务端在事务行锁内校验版本，只修改目标子任务的 done/status 并追加活动记录，保留其他字段。冲突时返回工具错误并要求重读，不自动重试；返回更新后的主任务及新版本。历史无 id 的子任务使用 taskId#下标。

待验证写入 done=false；完成时 done=true 并清除细分状态，重新打开为未开始时 done=false。AI 默认只推进到待验证，已完成由用户验收决定。

CLI：`wao subtasks status SUB_ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION`。部署后必须先通过实时 tools/list 确认新工具可见；不得在旧服务尚未部署时猜测调用。

## 参考图库

本次新增的收图和带图提问只面向浏览器会话及本地 Codex Runner：涉及二进制个人截图、按成员鉴权的原图和精确运行领取令牌。现有工作区 MCP 令牌不自动获得私有原图读取能力，因此本次不新增 `wao` 命令。出现明确的终端批量收图需求时，应先设计独立读写授权和二进制输入协议，再同步 CLI 与测试。

## 小记列表图片预览

`GET /api/images/{id}/preview` 只为浏览器列表提供原图的轻量展示字节，不创建、修改或删除图片。它沿用公开原图的读取边界，CLI 当前没有独立预览操作；若以后需要终端批量导出缩略图，再设计二进制下载命令及缓存、授权契约。

图片和附件改用私有 S3 兼容存储后，既有 HTTP URL、读取权限和上传操作保持原契约。对象迁移与校验是部署管理员操作，不向工作区 MCP 令牌开放对象密钥、桶访问或 MySQL 正文清理，因此本次不新增 CLI 命令；若将来需要用户在终端批量上传图片，再按成员权限设计专用二进制传输命令。

音视频 `/api/files/{id}/stream` 的 Range 读取优化仅改变浏览器播放时从私有对象存储获取字节的方式，不增加用户可执行操作，因此不新增 CLI 命令；若以后提供终端媒体下载，再单独设计票据与分段下载的命令契约。

## 我的准则

我的准则已按 ADR-295 全站下线；CLI 不提供该功能命令。

## 小记 AI 挖掘

内容候选需要在浏览器中同时核对生成理由、来源小记版本并给出本人反馈；提醒草稿还必须明确区别于已启用提醒。现有工作区 MCP 令牌没有这组成员私有 AI 产物和反馈权限，本次不新增 CLI 命令，也不把 `/api/note-mining` 自动暴露为 MCP 工具。出现终端批量导出或自动化挖掘需求时，先设计来源选择、版本快照、产物审计和独立授权，再同步 CLI 与测试。

## 小记最新正文自动整理

`notes-single-latest-content.md` 统一浏览器编辑、预览和后台润色写回；本人通过现有文档 PATCH 保存后，既有工作流自动续跑最新正文。本次不增加终端操作或扩大 MCP 令牌权限；终端批量润色仍需带源版本和独立授权的业务命令，届时重新评估 CLI。


### 图片 Tab 缩进边界

浏览器选中图片的键盘缩进只扩展既有文档内容的安全排版属性 `image.attrs.indent`（0–8），不增加独立业务操作。现有文档读写、分享和权限沿用；CLI/MCP 不新增命令或扩大令牌权限。若以后提供通用结构化排版编辑命令，再统一评估。

## 本机小记 AI 执行

小记阶段异常恢复沿用已有成员状态和重试接口，新增的失败快照保护只在服务内部使用，不新增终端操作、MCP 工具或令牌权限，见 [当前架构](../architecture.md)。

小记失败原因保留仅调整既有状态响应的 message 持久化语义，没有新增可操作能力，不新增 CLI / MCP；见 [当前架构](../architecture.md)。

小记批次优先接续已入队阶段仅修复后台队列推进，不新增用户操作、CLI / MCP 或权限，见 [当前架构](../architecture.md)。

小记串行成员上限沿用既有个人偏好和工作流状态，仅修正调度，不新增 CLI / MCP 操作或令牌权限；连接内隔离开关仅供本地主机验收，见 [当前架构](../architecture.md)。

ADR-308 将天人一卦既有浏览器解读接入同一本机执行器，新增本人只读执行状态。现有 MCP 没有私有算卦权限，不增加 CLI 命令或扩大旧令牌；终端能力需另行设计成员授权和幂等语义，见 [当前架构](../architecture.md)。

ADR-306 恢复浏览器小记配图，以显式配置的本机 Codex 执行标题、润色、标签和 PNG 生成。服务主机的进程调用不是新的终端业务权限；沿用登录成员 HTTP API，不添加 Runner、MCP 或 CLI 领取 / 回调能力，不扩大旧令牌权限。终端批量处理仍需独立范围与版本条件设计。

## 天人一卦私有配图

天人一卦新增本人配图和提示词设置：继续属于成员浏览器私有解读链路。现有工作区 MCP/CLI 令牌没有起卦、私有会话或图片读取授权，因此不新增命令或扩大旧权限。以后若需要脚本批量操作，应另行设计成员授权、配图幂等和私有图片获取边界；接口见 [当前架构](../architecture.md)。

## 本机 Codex 额度采集

浏览器模型选择改用 [当前架构](../architecture.md)。目录属于服务主机已有 ChatGPT 登录环境；现有工作区令牌不包含此读取权限，不自动新增 CLI/MCP 命令。以后提供终端模型选择时需同时设计账号环境权限，不能仅开放 HTTP 路由。

ADR-307 通过服务主机已有 ChatGPT 登录只读采样，沿用 `/api/codex-memberships` 展示，不新增对外操作。采集开关与 CLI 路径是主机配置，不向旧 MCP 令牌开放读取主机账号或执行进程的权限，因此不新增 `wao` 命令；跨机器复用数据库中的脱敏快照。

## 小记顺手发现

[当前架构](../architecture.md) 为浏览器成员私有、明确同意的持续后台分析；设置、发现、来源与独立反馈不新增 MCP/CLI 工具。当前工作区令牌没有此项持续分析授权，不能因 Web API 增加而自动扩权。既有合法文档写入路径在服务层可靠记录变化，不改变原工具权限。旧手动启动与图片重试返回 410；历史内容保留。终端若需此能力应另行设计成员授权、暂停/预算和来源权限，不在本期开放。

私人小记原稿、保存前 AI 输入及结果关联仅面向真实会话成员，不新增 CLI/MCP 命令或扩展通用文档令牌；按篇读取与真实性边界见 [当前架构](../architecture.md)。

[当前架构](../architecture.md) 复用公开链接投影与已有回调，仅调整展示和图片上传，不新增 CLI/MCP 操作。私有卦象配图提供者为服务内部协议，不能自动扩大旧令牌权限。

统一 AI 设置新增天人成员私有模型/深度选择，继续依赖浏览器登录和本机模型目录；没有新增工作区令牌权限，因此本次不增加 CLI/MCP 操作。终端配置需另行定义成员授权和 CAS 契约。

## 首页日期提醒

[当前架构](../architecture.md) 是浏览器会话成员的生日、节气和重要日投影。实际展示租约/回执依赖浏览器生命周期，不适合由脚本冒充「已显示」；既有工作区令牌没有私有日历分类与提醒渠道配置权限，因此本次不新增 MCP/CLI、不扩权。以后若需终端管理日历，重审成员授权、私密字段与版本条件；HTTP 见 [当前架构](../architecture.md)。

## 小记 AI 伴读

成果私有历史、字段级采用/恢复/取消、已读回执及按篇私人对话由浏览器会话授权，当前 MCP 令牌没有这类模型调用与私人历史权限，因此不新增 CLI 工具，不扩大旧授权。复用既有 notes create/update 和任务草稿创建能力。以后开放终端需设计独立按篇权限、CAS、取消、幂等与来源隔离；HTTP 契约见 notes-ai-companion-2026-10-05.md。


## 飞书机器人统一管理

机器人注册表、共享应用密钥、消息接收路由与真实活动诊断仅开放管理员浏览器会话，不新增 CLI/MCP 设备令牌能力；现有普通待办和小记操作仍以实际 tools/list 为准。禁止模拟真实飞书来源，旧令牌不自动扩权。具体契约见 [当前架构](../architecture.md)。

同日修订 [当前架构](../architecture.md)：午夜任务与飞书认证回调不是可任意手动运行的终端能力，归档开关仍为管理员浏览器配置，不新增 CLI/MCP、不扩大旧令牌权限；旧批量同步不恢复。

## 小记图文整理

图文生成和原文快照由本人浏览器会话授权，本次不向 CLI 或旧 MCP 令牌开放；需要独立模型调用权限后再提供终端命令。HTTP 见 docs/api.md。

2026-10-29 浏览器待办工作台新增任务标签、个人收集箱及象限配置；本轮不新增 CLI 命令，不改变现有工作区令牌授权。
