# WAO CLI 规格与能力台账

## 目标

在终端和脚本中使用 `wao` 操作已获授权的 WAO 业务能力，并让 Codex 等 AI 可通过标准命令调用。首版直接使用工作区 MCP；认证与权限由服务端设备令牌决定，CLI 不绕过业务服务，也不因为新增 HTTP API 就自动开放新能力。

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
| 任务写入 | `create_task`、`update_task` | `tasks create`、`tasks update ID` |
| 知识库读取 | `list_knowledge_bases`、`get_knowledge_base` | `knowledge list`、`knowledge get ID` |
| 知识库写入 | `create_knowledge_base`、`update_knowledge_base`、`move_knowledge_node` | `knowledge create`、`knowledge update ID`、`knowledge move` |
| 文档读取 | `list_documents`、`get_document` | `documents list KB_ID`、`documents get ID` |
| 文档写入 | `create_document`、`update_document` | `documents create`、`documents update ID` |
| 文件夹写入 | `create_folder`、`rename_folder` | `folders create`、`folders rename` |

## 后续能力同步

新增或修改业务服务、HTTP API、MCP 工具时，在同一个需求中审视对应用户操作是否需要 CLI。需要时维护服务端工具目录或适当的认证接口、CLI 子命令、此台账及权限和端到端测试；暂不适合开放时，在相关规格或 ADR 中写明原因和以后触发条件。通用 `tools call` 让新增 MCP 工具可立即被显式调用，但不能代替常用命令的可发现性、帮助文案与测试。不得用“所有 API 自动映射”替代逐项权限设计。

## 子任务待验证兼容

`get_task` 返回的内嵌子任务 `status` 可为「待验证」，此时 `done=false`。`update_task` 仅修改主任务，不支持独立子任务写入；CLI 同样不开放该操作，避免绕过子任务并发保护或扩大令牌权限。新增写入能力时需同步工具、权限、冲突测试和 CLI。
