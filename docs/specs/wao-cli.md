# WAO CLI 规格与能力台账

## 目标

在终端和脚本中使用 `wao` 操作已获授权的 WAO 业务能力，并让 Codex 等 AI 可通过标准命令调用。首版直接使用工作区 MCP；认证与权限由服务端设备令牌决定，CLI 不绕过业务服务，也不因为新增 HTTP API 就自动开放新能力。

CLI 支持通过独立授权的现有托管页 API 发布一个公开 HTML 页面；命令、确认交互、文件校验与匿名内容核验见 [托管页发布规格](phase-1-cli-hosted-page-publish.md)。此能力不映射为 MCP 工具，也不授予列表、修改或删除页面的权限。

普通文件业务上限调整为 200MB 只改变既有浏览器文件上传与系统管理员设置；工作区 MCP 令牌没有系统设置写权限，本次不新增 CLI 命令。未来需要脚本化配置时，先设计独立的管理员授权及审计（ADR-296）。

小记阅读布局、成员级标题 / 配图 / 润色提示词与 GPT 模型选择只作用于浏览器中已保存小记的个人阅读增强流程；现有 MCP 没有批量笔记变更工具，本次不新增 CLI 或扩展旧令牌权限。浏览器图片请求的幂等请求号只用于同一登录会话在网络重试后续接原任务，不是终端操作能力。未来若支持终端批量处理，需另行设计有界选择、文档版本条件与冲突确认。

小记 AI 阶段的并行 / 串行模式与并发数只控制当前成员在浏览器保存新小记时的自动整理调度；它不提供终端选择、批量处理或新的数据操作权限，因此不新增 `wao` 子命令或 MCP 工具。若以后允许从 CLI 批量发起小记 AI 任务，需重审成员并发配额、版本冲突和任务恢复语义。

小记智能标签推荐同样是保存后针对单篇正文、小记标签字典与当前文档版本的浏览器编辑步骤；`NOTE_TAGS` 不构成独立的终端批量操作，因此不新增 CLI 子命令或工作区 MCP 工具。服务端只读任务按现有标签字典二次校验 AI 结果。

项目列表的个人分组与排序只影响当前成员的浏览器展示，复用成员偏好接口且不改变共享项目字段。它不构成独立业务操作，CLI 和工作区 MCP 不开放通用成员偏好写入，也不新增分组命令；未来若出现脚本化布局需求，需先定义字段白名单、并发冲突与操作审计。

## 命令与契约

- Node.js 22+；仓库内 `wao-cli` 包通过 `npm link` 提供 `wao` 命令。默认远程地址为 `https://wao.wayuwa.cn/api/mcp/workspace`。
- 首页连接弹窗提供单独的“复制令牌（用于 CLI）”；交互登录接受 `wao_workspace_` 令牌或包含唯一该令牌的连接说明，验证后在 Windows 使用当前用户 DPAPI、在 macOS 使用系统钥匙串保存。记忆 MCP 令牌及没有工作区令牌的文本在发出网络请求前拒绝。`WAO_TOKEN` 供自动化或其他系统使用并覆盖本机安全凭据。CLI 不将令牌写入本地明文配置或日志。
- `wao tools list` 使用实时 `tools/list`。`wao tools call NAME --input-file FILE` 提供未封装工具的通用入口；调用前按令牌可见工具校验。常用子命令使用同一 schema，字段以 `--kebab-case` 输入。
- `--output json` 输出 `structuredContent.data`，默认数组用表格、对象用 JSON；所有错误进入 stderr 且退出非零。CLI 不自动重试写入。远程 HTTP 被拒绝，本机 HTTP 仅供测试。
- CLI 不提供删除、直接通知、浏览器会话管理或令牌签发命令。令牌签发和吊销仍在 WAO 页面进行。

## 当前覆盖

音乐功能已按 ADR-300 下线，播放器与网易云专属 API 不再提供。CLI 与工作区 MCP 原本没有专属音乐能力，本次不新增替代命令；通用文件资产及其音视频流读取保留。

| 领域 | MCP 工具 | CLI 子命令 |
| --- | --- | --- |
| 项目读取 | `list_projects`、`get_project` | `projects list`、`projects get ID` |
| 项目写入 | `create_project`、`update_project` | `projects create`、`projects update ID` |
| 任务读取 | `list_open_tasks`、`get_task` | `tasks open`、`tasks get ID` |
| 子任务状态 | `update_subtask_status` | `subtasks status ID` |
| 任务写入 | `create_task`、`update_task` | `tasks create`、`tasks update ID` |
| 知识库读取 | `list_knowledge_bases`、`get_knowledge_base` | `knowledge list`、`knowledge get ID` |
| 知识库写入 | `create_knowledge_base`、`update_knowledge_base`、`move_knowledge_node` | `knowledge create`、`knowledge update ID`、`knowledge move` |
| 文档读取 | `list_documents`、`get_document` | `documents list KB_ID`、`documents get ID` |
| 文档写入 | `create_document`、`update_document` | `documents create`、`documents update ID` |
| 文件夹写入 | `create_folder`、`rename_folder` | `folders create`、`folders rename` |
| 托管页公开发布 | 独立托管页 API（非 MCP 工具） | `pages publish`（仅新建，不提供列表、修改、删除） |

## 后续能力同步

WeKnora 目标库允许同时启用 Wiki 与向量索引，只改变既有内容同步的目标库校验，不新增用户操作或授权边界。内容同步仍通过 WAO 登录会话和受限机器密钥执行；现有工作区 MCP 令牌没有同步配置与执行权限，故本次不新增 CLI 命令。若需要无人值守的终端同步，再设计专用权限与运行状态查询后重审。

账务本已按 ADR-295 全站下线；CLI 与工作区 MCP 均不提供账务本命令或工具。

新增或修改业务服务、HTTP API、MCP 工具时，在同一个需求中审视对应用户操作是否需要 CLI。需要时维护服务端工具目录或适当的认证接口、CLI 子命令、此台账及权限和端到端测试；暂不适合开放时，在相关规格或 ADR 中写明原因和以后触发条件。通用 `tools call` 让新增 MCP 工具可立即被显式调用，但不能代替常用命令的可发现性、帮助文案与测试。不得用“所有 API 自动映射”替代逐项权限设计。

## 子任务状态修改（2026-09-24）

用户明确要求开放独立子任务状态更新，新增 `update_subtask_status`，只向现有可读写令牌开放；每次调用继续检查成员项目可见性，只读令牌不可调用。`update_task` 仍仅修改主任务。

必填参数：`task_id`、`subtask_id`、`status`、`expected_subtask_version`。状态为未开始、进行中、待验证或已完成。先 `get_task` 读取 `subtaskVersion`，将其作为 expected_subtask_version。服务端在事务行锁内校验版本，只修改目标子任务的 done/status 并追加活动记录，保留其他字段。冲突时返回工具错误并要求重读，不自动重试；返回更新后的主任务及新版本。历史无 id 的子任务使用 taskId#下标。

待验证写入 done=false；完成时 done=true 并清除细分状态，重新打开为未开始时 done=false。AI 默认只推进到待验证，已完成由用户验收决定。

CLI：`wao subtasks status SUB_ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION`。部署后必须先通过实时 tools/list 确认新工具可见；不得在旧服务尚未部署时猜测调用。

## 参考图库（ADR-278）

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

2026-10-02：项目与任务 AI Run 已下线（ADR-303），不提供派工、续跑或队列查询 CLI / MCP 命令；历史业务数据保留。

2026-10-02：WAO Runner 配对和执行协议已下线（ADR-304），工作区 MCP / CLI 无专属命令。获准的服务端 AI API 保留，旧 Runner 账号不再属于可执行能力。

2026-10-02：WeKnora 及派生同步退役（ADR-305）。工作区 MCP / CLI 不提供同步配置或执行能力，WAO 原生知识库命令保留。


### 2026-10-02 图片 Tab 缩进边界

浏览器选中图片的键盘缩进只扩展既有文档内容的安全排版属性 `image.attrs.indent`（0–8），不增加独立业务操作。现有文档读写、分享和权限沿用；CLI/MCP 不新增命令或扩大令牌权限。若以后提供通用结构化排版编辑命令，再统一评估。

## 本机小记 AI 执行

ADR-306 恢复浏览器小记配图，以显式配置的本机 Codex 执行标题、润色、标签和 PNG 生成。服务主机的进程调用不是新的终端业务权限；沿用登录成员 HTTP API，不添加 Runner、MCP 或 CLI 领取 / 回调能力，不扩大旧令牌权限。终端批量处理仍需独立范围与版本条件设计。

## 本机 Codex 额度采集

ADR-307 通过服务主机已有 ChatGPT 登录只读采样，沿用 `/api/codex-memberships` 展示，不新增对外操作。采集开关与 CLI 路径是主机配置，不向旧 MCP 令牌开放读取主机账号或执行进程的权限，因此不新增 `wao` 命令；跨机器复用数据库中的脱敏快照。
