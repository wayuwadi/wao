# WAO CLI

`wao` 使用现有工作区 MCP；托管 HTML 与 Pet ZIP 使用同一工作区令牌的专用 HTTP 接口。MCP 命令权限取自当前令牌的 `tools/list`，Pet 每次先检查对应操作快照。需要 Node.js 22+。

## 安装与连接

在仓库内执行 `cd wao-cli && npm link`，之后可在终端运行 `wao help`。无需安装第三方依赖。

在 WAO 能力中心 → 我的连接（`/capabilities?area=connections`）创建 CLI/MCP 新授权，逐项选择需要的操作及项目 / 知识库范围，复制生成的**工作区**令牌。运行 `wao auth login`，按提示粘贴以 `wao_workspace_` 开头的令牌；CLI 先验证服务端，再将令牌存入本机安全凭据库（Windows 使用当前用户 DPAPI，macOS 使用系统钥匙串）。也可粘贴包含唯一工作区令牌的完整连接说明。自动化脚本可设置 `WAO_TOKEN` 环境变量；该变量优先于本机凭据。不要把令牌写进命令参数、Git 或脚本文件。已有令牌固定原操作授权，升级 CLI、重新登录或新工具部署都不会扩权；使用新增能力须在能力中心创建包含相应操作的新授权。`wao auth status` 可验证连接，`wao auth logout` 仅删除本机凭据，不会吊销服务端令牌；吊销请在能力中心完成。

默认连接 `http://localhost:9000/api/mcp/workspace`。连接线上 WAO 可将 `WAO_URL` 设为 `https://wao.wayuwa.cn/api/mcp/workspace`，也可对单次命令使用 `--url`。远程地址必须是 HTTPS，本机测试允许 HTTP。

## 开发服务授权与密码本

在 WAO「能力中心 → 我的连接」创建 CLI 授权，按需勾选「密码本」下的查找和保存，并用 `wao auth login` 写入系统凭据库。取一个能区分服务、环境和账号用途的条目名。同名多账号用 `--account` 精确匹配。

```sh
wao vault find 'Codeup 工作账号' --output json
wao vault save '开发平台 Test' --account '测试账号'
wao vault save 'Codeup 工作账号' --from-codeup-keychain
```

默认由本人在终端隐藏输入。已有 macOS `codeup-cli` 钥匙串条目时，第三条命令显示 WAO 目标地址并由本人确认，然后直接保存该授权，不用再次输入。支持安全导出凭据的受信任程序也可以将 UTF-8 凭据原样通过管道传给 `--secret-stdin`（最多 8192 字节，换行也属于输入）。使用 `WAO_URL` 或 `--url` 指定自己的 WAO 工作区，保存授权以目标实例为准。

查找和保存回执只包含条目标识、名称及凭据状态。重复保存会保留已有凭据，替换或删除可打开 WAO 密码本处理。WAO 用 HTTPS 和服务端加密保护凭据。密码本条目仍受外部平台的凭据有效期、设备限制和撤销策略约束。对于仅发浏览器 Cookie 或一次性验证码的平台，在原客户端完成授权。

`wao connections codeup status PR编号` 使用已配置的本机 `codeup-cli status` 和它的系统授权，返回 PR 编号、状态、目标分支和合并提交。默认寻找 `~/.local/bin/codeup-cli`，也可在本机环境配置 `WAO_CODEUP_CLI` 为可执行文件的绝对路径。Codex 可通过本机 stdio MCP 使用同一查询：

```sh
codex mcp add wao-local-connections -- wao connections mcp
```

Codex 进程找不到 `wao` 时，用其安装后的绝对路径。MCP 提供 `codeup_pr_status`。Codeup 的日常查询继续使用本机 CLI 管理的有效授权，密码本可留存用户主动备份的授权。查询遇到授权失效时，由本人更新 Codeup 的本机授权后重试。

### 星狮 DevOps

安装团队的星狮 DevOps CLI（macOS 版）。在能力中心新建 CLI，选择「仅星狮授权」快捷方案。设定 `WAO_URL` 后用 `wao connections devops authorize` 配置专用 WAO 令牌，通用工作区令牌继续独立保存。

```sh
wao connections devops status
wao connections devops environments
wao connections devops sync
wao connections devops restore
```

查询先从 WAO 密码本获取当前设备的加密记录并向星狮验证，记录缺失或过期时再验证团队 CLI 的本机授权。仅两个来源都缺失或被星狮拒绝认证时返回 `AUTH_REQUIRED`。通过团队 CLI 的 `--json auth login` 完成一次扫码，再用 `sync` 保存新的授权。`restore` 可把有效的 WAO 设备授权恢复到团队 CLI 的本机钥匙串，已有有效本机授权时予以保留。密钥由 macOS 钥匙串保存，授权可在同一设备上由后续 Codex 任务直接复用。

使用已安装的 `~/.local/bin/cli-anything-devops`，可由本机配置 `WAO_DEVOPS_CLI` 指定等效安装路径。Codex 本机 MCP 可调用 `devops_environments`，工作区地址可通过 MCP 配置的 `WAO_URL` 或启动参数 `--url` 指定。MCP 查询只回报可访问的环境代码和状态。密码本可删除该设备的备份，能力中心可撤销设备访问。

### MySQL MCP

macOS 客户端复用 Codex 已配置的 MySQL 实例和 `codex.mysql-instance` 钥匙串。实例配置默认位于 `~/.codex/config/mysql-instances`，可用 `WAO_MYSQL_INSTANCE_DIR` 指定。把 `WAO_MYSQL_DBHUB_ENTRY` 指向已安装的 DBHub `dist/index.js` 绝对路径，在 WAO 能力中心签发「仅 MySQL 授权」CLI 令牌，用 `wao connections mysql authorize` 存入本机钥匙串。每条命令和 MCP 启动都使用同一 `WAO_URL`。

```sh
wao connections mysql status mysql-test
wao connections mysql sync mysql-test
codex mcp add mysql-wao-test --env WAO_URL="$WAO_URL" --env WAO_MYSQL_DBHUB_ENTRY="$WAO_MYSQL_DBHUB_ENTRY" -- wao connections mysql serve mysql-test
```

`status` 先验证密码本授权，再检查本机钥匙串。`sync` 可直接保存通过真实只读探针的本机密码。MCP 启动时优先使用密码本。密码本缺记录或凭据被 MySQL 拒绝时，MCP 可验证本机凭据并加密保存后启动。网络、TLS 或默认库权限错误需要排查连接。两个来源都缺失或无法通过认证时，由本人通过原有 MySQL 配置工具更新指定实例。只读 MCP 提供 `execute_sql` 和 `search_objects`，查询上限为 200 行。

如需撤销仍持有的一枚 WAO 工作区令牌，可通过交互隐藏输入或 `--token-stdin` 使用 `wao auth revoke-self`。WAO 只撤销本次输入的令牌。

### 小程序 Auth

在 macOS 的 `~/.config/wao/applets/配置名.json` 配置原小程序的 Passport 来源、客户端标识和 App ID。可用 `WAO_APPLET_PROFILE_DIR` 指定本机配置目录，WAO 连接需设置 `WAO_URL`。以下是配置格式示例，实际字段值从相应原小程序的 Test 配置获取。

```json
{
  "origin": "https://passport.example.test",
  "clientType": "example-user-applet",
  "appId": "wx0123456789abcdef"
}
```

在能力中心签发「仅小程序 Auth 授权」CLI 令牌，用 `wao connections applet authorize` 隐藏输入并存入本机钥匙串。接着运行 `wao connections applet status 配置名`，也可通过本机 stdio 工具 `applet_status` 查询。有效授权经 Passport 的用户信息接口回验后直接复用。仅有 `missing` 或 `expired` 时才需要首次绑定或更换会话，网络和接口错误单独报告。

绑定时用 `wao connections applet import 配置名` 隐藏输入原客户端授权的会话，也可由原客户端的受信任工具直接管道传给 `--session-stdin`。原客户端提供本次微信登录临时代码时可用 `wao connections applet login 配置名` 隐藏输入，或用 `--code-stdin` 接收，CLI 通过正式 Passport 登录接口交换并回验会话。每次先检查已有设备授权，有效时无需输入。普通密码本条目可由本人在密码本中选择后通过 `import` 完成一次设备绑定。后续会话更新须匹配已绑定的账号，同一应用的不同账号使用各自的配置名。

## 使用

```bash
wao tools list
wao pages publish --file dist/index.html --title "WAO 设计稿" --slug wao-design --project-id PROJECT_ID
wao pets list
wao pets upload --file curly-trio.zip --source "本人创作" --license ALL-RIGHTS-RESERVED
wao pets share PET_ID --yes
wao pets unshare PET_ID
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

## Pet 命令（0.3.0）

`pets list` 查看本成员的 Pet。Pet 的工作区地址路径必须是 `/api/mcp/workspace`（末尾斜线兼容），不支持部署路径前缀；公开链接和匿名 API 位于同源根路径。`pets upload --file PET.zip --source 素材来源` 接受大于 0 且不超过 10 MiB 的 ZIP；服务端继续验证清单、精灵图和包结构。素材来源必填，去除首尾空格后为 1–500 字。授权默认 `ALL-RIGHTS-RESERVED`，也可选 `MIT`、`Apache-2.0`、`CC-BY-4.0`、`CC0-1.0`。上传创建私有 Pet，同一成员的相同规范化内容、来源和授权由服务端复用既有包；复用已经公开的包时保持其当前公开状态，上传命令不会改变可见性。

`pets share ID` 是单独的公开操作，会展示 Pet ID、公网链接并要求确认拥有素材分享权利，输入 `yes` 后提交 `rightsConfirmed=true`。脚本须显式使用 `--yes`。`pets unshare ID` 取消公开，无需再次确认。浏览器中的发布者身份需已经设置；CLI 不代替用户创建发布者身份。三个授权操作分别为“查看我的 Pet”（`list_my_pets`）、“上传 Pet”（`upload_pet`）、“分享/取消分享 Pet”（`share_pet`），必须在能力中心 CLI/MCP 新授权中逐项选择；旧令牌不会自动扩权。CLI 在上传任何 ZIP 字节或修改可见性前检查授权，并拒绝跟随携带令牌的 HTTP 重定向。Pet 二进制上传不经 MCP tools/call。

上传先在既有操作回执目录持久保存输入 ZIP 的 SHA-256、来源、授权及端点；默认回执名由这些字段稳定生成，相同输入再次执行时先要求用 `pets list` 核对旧结果，不会重新 POST。`--receipt-file NEW_FILE` 可指定一个尚未存在的持久回执文件。分享和取消分享也保存各自回执。请求结果不明时保留 `unknown` 状态，CLI 不自动重试。服务端可能重新打包 ZIP，回执中的 `archiveSha256` 是输入包摘要，返回卡片 `sha256` 是服务端规范化包摘要。

分享保存后，CLI 无令牌请求公开元数据和下载包，按卡片校验元数据、包大小和 SHA-256，下载严格限制为 10 MiB。通过后返回 `/pets/ID` 公网链接和 `verification.ok=true`。匿名校验失败会非零退出，并在回执中分别保留 `persistedListing` 与 `verification`；公开状态可能已经保存，应先检查链接或执行 `pets unshare ID`，不要凭失败退出推断分享未生效。`--output json` 保留卡片、公开链接、校验结果与回执路径，便于脚本核对。回执没有令牌，但包含业务元数据，应按用户数据保护。

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
wao subtasks promote SUBTASK_ID --task-id SOURCE_ID --expected-source-version SOURCE_VERSION
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
