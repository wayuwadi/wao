# WAO CLI 规格与能力台账

## 目标

在终端和脚本中使用 `wao` 操作已获授权的 WAO 业务能力，并让 Codex 等 AI 可通过标准命令调用。首版直接使用工作区 MCP；认证与权限由服务端设备令牌决定，CLI 不绕过业务服务，也不因为新增 HTTP API 就自动开放新能力。

人工托管 HTML 的存储兼容层只改变既有公开页面的浏览器运行方式，不新增 HTTP API、MCP 工具或 CLI 命令；CLI 当前不负责二进制 HTML 上传。

## 命令与契约

- Node.js 22+；仓库内 `wao-cli` 包通过 `npm link` 提供 `wao` 命令。默认远程地址为 `https://wao.wayuwa.cn/api/mcp/workspace`。
- 首页连接弹窗提供单独的“复制令牌（用于 CLI）”；Mac 交互登录接受 `wao_workspace_` 令牌或包含唯一该令牌的连接说明，验证后写入系统钥匙串。记忆 MCP 令牌及没有工作区令牌的文本在发出网络请求前拒绝。`WAO_TOKEN` 供自动化或其他系统使用并覆盖钥匙串。CLI 不将令牌写入本地明文配置或日志。
- `wao tools list` 使用实时 `tools/list`。`wao tools call NAME --input-file FILE` 提供未封装工具的通用入口；调用前按令牌可见工具校验。常用子命令使用同一 schema，字段以 `--kebab-case` 输入。
- `--output json` 输出 `structuredContent.data`，默认数组用表格、对象用 JSON；所有错误进入 stderr 且退出非零。CLI 不自动重试写入。远程 HTTP 被拒绝，本机 HTTP 仅供测试。
- CLI 不提供删除、直接通知、浏览器会话管理或令牌签发命令。令牌签发和吊销仍在 WAO 页面进行。

## 当前覆盖

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

## 后续能力同步

WeKnora 目标库允许同时启用 Wiki 与向量索引，只改变既有内容同步的目标库校验，不新增用户操作或授权边界。内容同步仍通过 WAO 登录会话和受限机器密钥执行；现有工作区 MCP 令牌没有同步配置与执行权限，故本次不新增 CLI 命令。若需要无人值守的终端同步，再设计专用权限与运行状态查询后重审。

账务本当前只通过 WAO 浏览器会话使用。逐笔财务数据只对账本创建者与明确受邀成员开放；现有工作区 MCP 设备令牌没有账本级授权，故本次不增加 MCP 工具和 CLI 子命令。若将来有终端批量对账需求，应先设计账本级授权、最小字段范围与修改审计，再同步 CLI。

新增或修改业务服务、HTTP API、MCP 工具时，在同一个需求中审视对应用户操作是否需要 CLI。需要时维护服务端工具目录或适当的认证接口、CLI 子命令、此台账及权限和端到端测试；暂不适合开放时，在相关规格或 ADR 中写明原因和以后触发条件。通用 `tools call` 让新增 MCP 工具可立即被显式调用，但不能代替常用命令的可发现性、帮助文案与测试。不得用“所有 API 自动映射”替代逐项权限设计。

## 子任务状态修改（2026-09-24）

用户明确要求开放独立子任务状态更新，新增 `update_subtask_status`，只向现有可读写令牌开放；每次调用继续检查成员项目可见性，只读令牌不可调用。`update_task` 仍仅修改主任务。

必填参数：`task_id`、`subtask_id`、`status`、`expected_subtask_version`。状态为未开始、进行中、待验证或已完成。先 `get_task` 读取 `subtaskVersion`，将其作为 expected_subtask_version。服务端在事务行锁内校验版本，只修改目标子任务的 done/status 并追加活动记录，保留其他字段。冲突时返回工具错误并要求重读，不自动重试；返回更新后的主任务及新版本。历史无 id 的子任务使用 taskId#下标。

待验证写入 done=false；完成时 done=true 并清除细分状态，重新打开为未开始时 done=false。AI 默认只推进到待验证，已完成由用户验收决定。

CLI：`wao subtasks status SUB_ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION`。部署后必须先通过实时 tools/list 确认新工具可见；不得在旧服务尚未部署时猜测调用。

## 参考图库（ADR-278）

本次新增的收图和带图提问只面向浏览器会话及本地 Codex Runner：涉及二进制个人截图、按成员鉴权的原图和精确运行领取令牌。现有工作区 MCP 令牌不自动获得私有原图读取能力，因此本次不新增 `wao` 命令。出现明确的终端批量收图需求时，应先设计独立读写授权和二进制输入协议，再同步 CLI 与测试。
