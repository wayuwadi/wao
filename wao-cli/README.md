# WAO CLI

`wao` 使用现有工作区 MCP，命令权限与当前令牌的 `tools/list` 完全一致。需要 Node.js 22+。

## 安装与连接

在仓库内执行 `cd wao-cli && npm link`，之后可在终端运行 `wao help`。无需安装第三方依赖。

在 WAO 能力中心 → 我的连接（`/capabilities?area=connections`）创建 CLI/MCP 新授权，逐项选择需要的操作及项目 / 知识库范围，复制生成的**工作区**令牌。运行 `wao auth login`，按提示粘贴以 `wao_workspace_` 开头的令牌；CLI 先验证服务端，再将令牌存入本机安全凭据库（Windows 使用当前用户 DPAPI，macOS 使用系统钥匙串）。也可粘贴包含唯一工作区令牌的完整连接说明。自动化脚本可设置 `WAO_TOKEN` 环境变量；该变量优先于本机凭据。不要把令牌写进命令参数、Git 或脚本文件。已有令牌固定原操作授权，升级 CLI、重新登录或新工具部署都不会扩权；使用新增能力须在能力中心创建包含相应操作的新授权。`wao auth status` 可验证连接，`wao auth logout` 仅删除本机凭据，不会吊销服务端令牌；吊销请在能力中心完成。

默认连接 `https://wao.wayuwa.cn/api/mcp/workspace`。自建环境可设置 `WAO_URL` 或对单次命令使用 `--url`。远程地址必须是 HTTPS，本机测试允许 HTTP。

## 使用

```bash
wao tools list
wao pages publish --file dist/index.html --title "WAO 设计稿" --slug wao-design --project-id PROJECT_ID
wao projects list
wao projects get PROJECT_ID
wao tasks open --limit 10
wao tasks open --mine-only false --project-id PROJECT_ID --output json
wao tasks create --project-id PROJECT_ID --title '准备发布说明'
wao documents get DOCUMENT_ID --output json
wao documents update DOCUMENT_ID --input-file update.json
wao tools call list_open_tasks --input '{"limit":5}'
```

常用子命令覆盖工作区项目、任务、子任务、知识库、文档、小记、标签、模板、检索及日安排，详见 `wao help`。参数名直接取自服务端实时工具 schema，例如 `project_id` 对应 `--project-id`。`wao tools describe NAME` 和业务命令的 `--help` 展示当前令牌可见的字段、类型和必填条件；尚未部署或无权使用的工具明确拒绝。复杂参数使用 `--input-file`，`--input-file -` 从标准输入读取 JSON 对象。工具新增后，可先用 `wao tools list` 和 `wao tools call NAME --input-file payload.json` 调用，再为常用操作增加子命令。

列表默认以表格显示；分页对象显示 `items`、总数量、偏移及是否还有下一页，`--output json` 保留原始分页对象，适合管道和脚本。错误写入标准错误并返回非零退出码。CLI 不自动重试写入；如遇超时，应先查询 WAO 当前状态。`wao --version` 查看 CLI 版本，`wao doctor` 或 `wao auth status` 查询连接、当前可用工具与授权入口；只有快照授权了 `get_workspace_info` 才会读取成员身份、权限和服务版本。旧服未提供或当前令牌未授权身份工具时明确提示身份不可查询，连接检测仍可成功，不探测未授权工具。

`wao pages publish` 会把单文件 HTML 公开发布到 WAO，并在完成后匿名访问公网链接，逐字节核对托管内容。能力中心 CLI/MCP 新授权必须选入“发布 HTML 页面”；项目归属还要在令牌授权范围内且对成员可见。命令会在发布前展示文件、标题、后缀、项目和公网地址并要求输入 `yes`；脚本可传 `--yes` 明确授权。CLI 不自动重试创建请求，遇到超时或核验失败时先检查已返回的链接，避免重复创建。

`update_document` 替换正文时，先用 `wao documents get` 读取当前 `rev`，再在输入 JSON 中提供 `expected_rev` 和完整 `blocks`，以便服务端阻止覆盖他人的编辑。

## 开发

运行 `npm test` 验证 HTTP、权限、参数和输出行为。新业务 API 或服务的 CLI 覆盖检查见 [项目 skill](../.codex/skills/wao-cli-sync/SKILL.md) 与 [CLI 规格](../docs/specs/wao-cli.md)。

子任务状态：先 `wao tasks get TASK_ID --output json` 获取 `subtaskVersion`，再执行 `wao subtasks status SUB_ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION`。工具须已由服务端 tools/list 返回，冲突时重新读取，不自动重试写入。

## 日常命令（0.2.0）

```bash
wao notes list --keyword 工作 --tags '["项目"]' --offset 0 --limit 20
wao notes create --title "发布回顾" --file article.md --tags '["工作"]'
wao notes create --title "系统功能介绍" --file article.md --tags '["新功能介绍"]' --system-generated true
wao notes list --include-system false
wao notes update DOCUMENT_ID --file article.md --expected-rev 7
wao notes tags add DOCUMENT_ID --tags '["工作"]' --expected-rev 7
wao notes tags remove DOCUMENT_ID --tags '["临时"]' --expected-rev 8
wao notes tags set DOCUMENT_ID --tags '["归档"]' --expected-rev 9
wao notes tags clear DOCUMENT_ID --expected-rev 10
wao tags list
wao tags create --name 工作
wao notes export DOCUMENT_ID --format md --file article.md
wao documents export DOCUMENT_ID --format json --file -
wao documents archived KB_ID --offset 0 --limit 20
wao documents archive DOCUMENT_ID --expected-rev 7
wao documents restore DOCUMENT_ID --expected-rev 8
wao templates create --name 周报 --source-document-id DOCUMENT_ID
wao templates update TEMPLATE_ID --description "每周回顾" --expected-updated-at TIMESTAMP
wao search --keyword 发布 --kinds '["task","note","document"]'
wao tasks list --mine-only true --offset 0 --limit 20
wao subtasks create TASK_ID --text "补充验证" --expected-edit-version VERSION
wao subtasks update SUBTASK_ID --task-id TASK_ID --due 2026-10-10 --expected-edit-version VERSION
wao subtasks reorder TASK_ID --subtask-ids '["s2","s1"]' --expected-edit-version VERSION
wao subtasks move SUBTASK_ID --task-id SOURCE_ID --target-task-id TARGET_ID --expected-source-version SOURCE_VERSION --expected-target-version TARGET_VERSION
wao tasks move TASK_ID --parent-task-id null --expected-parent-id PARENT_ID
wao tasks comment TASK_ID --text "验收记录"
wao comments update COMMENT_ID --task-id TASK_ID --text "修订验收记录"
wao tasks document TASK_ID --document-id DOCUMENT_ID --mode add --expected-edit-version VERSION
wao todo day 2026-10-04
wao todo days --from 2026-10-01 --to 2026-10-04
wao tasks schedule TASK_ID --expected-edit-version VERSION --start 2026-10-04 --start-at null --end-at null --schedule-period MORNING
wao daily record --business-date 2026-10-04 --file article.md
wao daily operation OPERATION_ID
```

文件正文必须是有效 UTF-8。`--file -` 从标准输入读纯文本或 Markdown，统一发送 `content={version:1,markdown:完整正文,document:null}`，保留标题、分段、列表等结构；不能与 `text` / `blocks` / `content` 正文或 JSON 标准输入混用。当前工具没有声明 `content` 时明确拒绝。JSON 参数也检查实时 schema 的字段、类型、必填与枚举；明确可空字段可使用命令行值 `null`。

导出 Markdown 优先使用高级正文 `content.markdown`，历史文档按 `blocks` 转换标题、段落、列表、引用及提示块，并附上文档标题；JSON 导出保留完整文档及版本。输出文件使用独占创建，已经存在时拒绝；确认覆盖须显式添加 `--force`，该开关只用于导出。`--file -` 输出到标准输出，便于管道。

`notes create` 和 `daily record` 在 schema 支持 `document_id` 时默认生成无横线 UUID；`tasks schedule` 和 `daily record` 默认生成 `operation_id` UUID，也可显式提供。日小记修改已存在文档时明确指定 `--document-id` 和 `--expected-rev`，关联既有任务时提供读取时的 `--expected-task-edit-version`；业务日期必须显式输入。发出写请求前，CLI 在 Windows `%LOCALAPPDATA%/WAO/cli/data/operations`（无 LOCALAPPDATA 则使用 APPDATA），其他系统 `~/.local/share/wao-cli/operations` 先创建回执，同时保存操作号及文档号，stderr 告知标识及路径；`--receipt-file NEW_FILE` 可指定新文件。回执已有时拒绝重复提交。成功保存返回结果；网络或业务错误保存 `unknown` 并保持标识，不能据此推断没有写入。根据标识运行 `daily operation` 或 `notes get` 核对状态后决定后续处理，不自动重试。回执不保存令牌，但可包含个人业务结果，应按用户数据保护。

排程需要完整提供 `start`、`start_at`、`end_at`、`schedule_period`，清空用 `null`；`due` 仍表示截止日期，不能拿来代替执行安排。文档、小记标签和任务编辑的版本字段从读取结果获得，CLI 不自动抓取新版本覆盖原请求。日读取、排程、日小记保存和回执查询分别需要当前令牌操作快照授权，写入还需写权限；`permissions.daily` 是从逐项 policy 快照推导的诊断指标，不是数据库权限开关。CLI 只按实际工具目录提供能力，不修改任何已有授权。

`documents list` 在工具 schema 包含 `offset` 时默认传 `offset=0,limit=50`，明确显示总数量及下一页；后续页使用 `--offset 50` 等参数。旧服务端没有分页字段时保持原数组调用。`tasks list --status open` 会包含主任务已完成但子任务未收尾的任务。跨任务子树移动的 `--expected-source-version` 和 `--expected-target-version` 来自双方的 `subtaskVersion`，子任务编辑的 `--expected-edit-version` 来自任务 `editVersion`。
