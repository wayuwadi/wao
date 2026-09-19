# WAO AI Runner

## 持续对话（2026-09）

新建 Codex 运行默认 GPT-6 Astra；轻量修改可在派工窗口选 Luna + medium，日常开发选 Terra + medium。
旧运行续聊沿用已固化模型，自定义 API 继续使用账号配置。建议节点 CLI 升级到支持 GPT-6 目录的官方版本；
升级不修改正在执行的任务或账号凭据。

同一任务请在「AI 执行记录」中继续输入，不必再创建运行。会话 ID 在建立时即绑定，运行、失败、成功和暂停后
都可续接；离线后固定回到原电脑和 worktree。公开对话可补读更早记录，输入草稿按用户和运行保存在本机。
复制会话 ID 只复制标识；在终端接续仍需原电脑的会话文件与同一 `CODEX_HOME`，集中账号会话位于
`<root>/.wao-codex-homes/<runId>`。请勿同时从终端和 AiRun 写同一会话。

Runner 在 `<root>/.wao-run-sessions/<runId>` 保存不含凭据的会话检查点。有检查点的 worktree 与隔离
Codex 目录不再按七天规则自动删除，避免一周后续聊失忆；这会增加长期磁盘占用，确认不再续聊后才能一起清理。
旧运行已经被清掉的目录或截断的历史正文无法补回。原电脑解除接入、目录丢失或会话绑定冲突会明确报错。

续聊只增加新输入，不重复召回首轮项目背景；讨论本身不要求空提交或构建。代码改动按实际范围验证，复用同一
Jenkins 五分钟发布批次和验收证据，不重复 Build 或例行 clean。

AI Runner 安装在娃哥、娃弟的 Windows 电脑和 Linux 主节点上。WAO 负责排队、展示运行状态，
并可按单次运行下发用户主动集中管理的 Codex / Claude Code 凭据或自定义 Responses API；
未选择集中账号时仍使用本机登录态。GitHub 登录态和源码始终保留在 Runner 本机。

开发任务的任务理解方法由项目「AI 工作流」中的「AI 执行提示词」配置。Runner 会把它放在任务原文地址前，
并继续追加不可由项目配置覆盖的工作区、提交、部署、真实性与最终状态约束；旧服务端缺少该字段时使用内置默认版本。

一台电脑可以同时装多种 AI CLI，Runner 启动时会自动探测本机装了哪些，并把结果作为「本机
能力」上报；WAO 只会把对应工具的任务派给它。

## 首次接入

1. 确认本机已有 Python 3.10+、Git，以及至少一种 AI CLI。使用执行电脑本地登录态时需提前登录；
   选择 WAO 集中账号时 CLI 本身仍必须安装，但不要求本机另做登录：
   - Codex CLI，执行过 `codex login`；
   - Claude Code CLI，执行过 `claude` 并完成登录；
   - Qoder CLI（国际版命令名 `qodercli`，国内版 `qoderclicn`），执行过一次并在 TUI 里 `/login` 完成登录。

   Windows 11 推荐使用 `winget install --id OpenAI.Codex --exact` 安装独立 Codex CLI。桌面应用也会注册
   `codex.exe`，但其受保护的内部路径不能供后台 Runner 启动；任务安装器会优先识别官方 winget CLI，
   并把绝对路径写入计划任务。
2. 打开 WAO 首页 AI 控制台 →「AI 工作流」，填写仓库地址、默认 AI 工具并开启开关。
3. 切到「执行电脑」点击“生成配对码”，十分钟内在对应电脑运行。

Windows（Codex）：

```powershell
python .\deploy\ai-runner\wao-ai-runner.py `
  --server https://wao.wayuwa.cn `
  --pair-code 页面显示的配对码 `
  --name 娃哥电脑 `
  --root D:\CodeSpace `
  --max-concurrency 2
```

Linux 主节点（Claude Code）：

```bash
python3 deploy/ai-runner/wao-ai-runner.py \
  --server https://wao.wayuwa.cn \
  --pair-code 页面显示的配对码 \
  --name Linux主节点 \
  --root ~/CodeSpace \
  --engines claude \
  --max-concurrency 2
```

Linux 主节点上的 Codex CLI 和 Runner systemd 配置属于主机级依赖，由管理员独立安装和升级。
CD 不再下载、上传、安装或重启 Codex CLI/Runner，避免业务发布被大文件传输和 AI 工具生命周期阻塞。
首次接入前需确认 `command -v codex` 可用，并在 Runner 启动参数或 systemd 环境中配置 `codex`
能力和集中账号同步；节点身份仍必须通过 AI 控制台的一次性配对码建立。

Linux1 自身没有可用的 `chatgpt.com` 出站时，不能只因为本机装了 `codex` 就直接接任务。按
[ADR-171](../../docs/adr/171-linux1-codex-egress-and-startup-timeout.md) 复用到 Linux2 的既有 SSH 通道：
以 `wao-linux2-tunnel.service.example` 为模板增加回环本地转发，再给 Linux1 Runner 增加
`--codex-proxy-url http://127.0.0.1:17897`。Runner 只把该代理交给 Codex；WAO、Git 和其它 AI 工具
不改路由。启动前会用真实 ChatGPT 接口探针验证转发，失败时自动重试并显示明确错误，不再无限停在
“正在启动 Codex”。安装后执行以下无凭据检查，401 表示已经到达真实服务：

```bash
curl --proxy http://127.0.0.1:17897 --output /dev/null \
  --write-out '%{http_code}\n' https://chatgpt.com/backend-api/wham/usage
```

`--engines` 留空表示自动探测（装了哪种就报哪种，可选 codex、claude、qoder）；显式指定时
找不到对应命令会直接启动失败，避免电脑上报了自己其实跑不了的能力。需要固定模型时加
`--claude-model opus`。Qoder 的引擎 id 是 `qoder`，命令名国际版叫 `qodercli`、国内版叫
`qoderclicn`，自动探测按顺序两个名字都认。

Runner 检测到 Codex 后，还会每五分钟通过本机 `codex app-server` 读取一次套餐、点数与真实限额，
把脱敏快照随心跳交给首页展示。同一账号在多台电脑登录时由服务端按邮箱合并；Token、Cookie 和
`~/.codex/auth.json` 不会上传。读取失败只保留一条脱敏诊断并沿用上次成功快照，不影响任务领取。

Runner 还会按服务端每 24 小时一次的指令，在空闲时检查官方 `CODEX_HOME/memories/` 本地记忆目录。
它只读取有界 UTF-8 普通文件，跳过符号链接、二进制和大文件并先遮盖常见凭据，再把内容作为不可信引用
交给关闭 shell、网页、MCP 和多 Agent 工具的隔离 Codex 会话。WAO 只收到“机器记忆”和“Codex 应用记忆”
两类结构化报告，原始文件、登录态和完整会话不会上传；报告先进入管理员审核，正文未变化也不会重复建版本。
若本机未启用 Codex Memories、目录为空或没有安全的稳定内容，节点只上报明确失败原因，六小时后重试，
不会创建占位记忆。

右下角 AI 助手会由任一在线且上报 `codex` 能力的本地 Runner 领取，Windows 和 Linux 均可；面板打开时
每 5 秒自动恢复一次短暂断开的状态，无需手动刷新整个页面。

选择 WAO 集中账号且没有手动指定执行电脑时，开发任务仍默认固定到支持对应 CLI 的 Linux 主节点。
因此要在 Linux 主节点运行 Codex，先确认 `command -v codex` 能找到命令，再重启 Runner 让它重新
上报能力：`systemctl --user restart wao-ai-runner`。页面中的 Codex 在线电脑数包含 Linux 后，集中账号
才会自动选中该节点；未安装时页面会明确提示，不会静默改派 Windows 个人电脑。

项目里只填一个 HTTPS 仓库地址，但每台电脑的 Git 认证方式可以不同：`--git-protocol` 默认
`auto`，本机有 HTTPS 凭据就用 HTTPS，没有（例如只配了 SSH key 的 Linux 主节点）就自动把
地址换成 `git@github.com:组织/仓库.git`。想固定行为可以显式传 `--git-protocol ssh` 或
`https`。切换协议后已经克隆过的仓库会自动更新 `origin`，不需要手工删目录。

新建的空 Git 仓库也可以直接派发任务。Runner 会为它创建一个孤儿任务分支和独立 worktree，不再尝试检出不存在的 `origin/main`；AI 在其中完成的首个提交就是该仓库的初始内容。

配对成功后，节点令牌只写入本机 `~/.wao/ai-runner.json`，不会进入项目或 Git。

### Windows 开机自启动与守护

首次配对成功后，用当前 Windows 用户执行：

```powershell
.\deploy\ai-runner\install-windows-task.ps1 `
  -Server https://wao.wayuwa.cn `
  -Name "我的 Windows 电脑" `
  -Root D:\CodeSpace\wao-airun `
  -MaxConcurrency 2
```

脚本会创建 `WAO AI Runner` 计划任务，包含开机、当前用户登录和每 1 分钟守护三个触发器，并在异常退出后自动重启。同目录存在 `pythonw.exe` 时优先用它无窗口运行，不再弹出或保留终端；缺少时才回退到 `python.exe`。Runner 在 Windows 上启动 Git、Codex、Claude Code、Qoder 和取消命令时统一使用系统的无窗口进程标志，因此任务成功、失败或取消都不会闪现子进程控制台；标准输入输出管道、文件日志和进程树终止仍然保留，隐藏窗口不会吞掉错误或留下失控进程。任务使用当前交互用户运行，因为 Codex、Git 和 GitHub 的登录凭据保存在用户配置中；因此无人登录时不能保证 AI CLI 可用。旧任务需重新执行一次安装脚本，才能把启动程序从 `python.exe` 更新为 `pythonw.exe`。

后台“远程唤醒电脑”使用 Wake-on-LAN。Runner 首次在线时会自动上报网卡地址，服务端自动记录来源地址，页面不要求用户填写 MAC、IP 或端口。目标电脑仍须由硬件支持 Magic Packet；跨公网是否可达取决于路由器和运营商网络。WAO 发送唤醒包后仍以 Runner 心跳恢复作为成功标准。
以后启动时不需要再次输入配对码。

Runner 会把启动信息、Git 与各 AI CLI 的命令路径和任务失败阶段写入 `~/.wao/ai-runner.log`。
每次心跳会把二次脱敏后的最近 200 行（最多 30,000 字符）保存为服务端快照；在 AI 控制台
「执行电脑」卡片点击“查看 Runner 日志”即可按需读取、刷新或复制。完整历史日志仍只在本机，
执行电脑列表不会携带日志正文。
页面“最近完成”中的失败记录会直接显示错误摘要，点击记录可查看完整失败原因、退出码、执行
工具和工作目录。日志达到 2 MiB 后自动轮换，不记录配对码和节点令牌。
任务提示通过标准输入交给 CLI，避免 Windows 的 `.cmd` 转发脚本截断包含链接的多行提示；
Runner 还会校验最终答复中的完成状态（`WAO_RUN_STATUS: SUCCESS` / `BLOCKED`），不再把反问、
等待补充信息误判为成功。
开发任务在 PR 合并后会先输出 `WAO_DELIVERY_STAGE: WAITING_DEPLOYMENT`。Runner 只从 AI 的文字消息识别
这一独占行，并把页面切到“代码已完成 · 等待部署”；命令、工具参数和任务正文里的同样文本不会触发。最终状态仍要等
包含本次提交的 Jenkins 批次、线上链路和飞书通知全部验证后回传。队列里更旧构建失败时继续等待后继批次，不把旧失败
复制成多条 AI Run 失败。
当 AI 以 BLOCKED 结束且保留会话 id 时，任务详情可直接补充信息；Runner 会把同一条运行固定派回
原电脑，在原 worktree 中恢复同一个 Codex 或 Claude Code 会话，而不是创建新会话。

项目设置中的“失败自动重试次数”控制 Git 工作区准备失败、AI CLI 非零退出或调用异常后的同运行恢复，默认 2 次、
范围 0 到 5。Git 准备与 AI CLI 分阶段计数；重试保留当前 worktree 和已有会话，中间错误只写进展与本机日志，
不提前完成运行或发送飞书失败通知；任一次恢复成功只产生最终成功通知，全部耗尽后才回传最后错误。AI 明确返回
`BLOCKED`、用户暂停或运行中补充输入不属于临时故障，不会被自动重试。

远端 Git 操作另有硬截止时间：首次克隆最多等待 5 分钟，日常 `fetch` 最多等待 60 秒。超时后 Runner
会终止 Git 及其 SSH/认证子进程，再进入上述自动恢复；这样单条失联链路不会长期占住同一项目的 Git 锁，
也不会阻塞节点脚本更新。只有已经确认 SSH key 可用时才应显式配置 `--git-protocol ssh`，其余节点保留
默认的 `auto`，让 Runner 按本机已有凭据选择 HTTPS 或 SSH。

页面助手、聊天复盘、人生复盘、周报、每日建议、定时预热、文档润色和点子调研共用的安全只读 Codex 链路，
遇到非零退出、调用异常或空答复时固定最多执行三次（首次加两次自动重试）。三次共用同一运行、临时空仓库、
安全参数和硬截止时间；中间部分输出不会回传，只有最终成功答复或第三次错误进入服务端终态。
启动时 Runner 会通过 `git credential fill` 读取本机已有的 GitHub HTTPS 凭据，并仅在子进程
环境中交给工作区 `git clone/fetch` 和 AI CLI，同时自动补齐 Windows 的 GitHub CLI 安装目录。
后台 Git 全程禁用交互式凭据询问，凭据失效时直接返回错误，不会尝试访问计划任务不存在的终端。
凭据不会写入 Runner 状态、命令行、日志或 Git 配置；因此只需让这台电脑的 Git 凭据管理器能
正常 `git push`，自动任务就可以继续创建 PR、合并和跟踪部署，不依赖 AI 工具内置的 GitHub
连接器。

运行期间 Runner 消费流式事件把用户可读进展实时回传首页与任务详情：

- Codex：`codex exec --json` 的 agent message、公开推理摘要、plan update 与阶段/结果。
- Claude Code：`claude -p --output-format stream-json` 的 assistant 文本与 TodoWrite 计划。

### 隔离联网模式

普通“问问 AI”会在时效性或外部事实需要核验时按需联网。本地 / 集中 Codex 能力直接在页面问答的隔离会话中
使用顶层 `--search`；选择 DeepSeek 或受信模型网关时，Runner 先生成带公开 URL 的有界联网材料，服务端再交给
用户选定的 Provider 生成最终答复，不会偷偷换掉所选模型。两条路径都继续禁用 Shell、多代理、图片、本机文件和
GitHub Token；内部只读分析、聊天复盘、文档润色与天人解读仍强制 `web_search=disabled`。

### 点子调研模式

点子库的 `IDEA_RESEARCH` 也通过 assistant-v2 安全协议领取，但落在独立的
`.wao-idea-research/<run-id>` 临时目录。它不克隆项目仓库，Codex 固定使用只读沙箱、临时
`CODEX_HOME`，禁用 Shell、多代理与图片工具，并剥离 GitHub Token；与普通页面问答一样用顶层
`--search` 开放实时 Web 搜索。无论成功、失败、
超时或回传失败，Runner 最后都会删除研究工作区与隔离认证目录。
- Qoder：`qodercli -p --output-format stream-json`，事件与 Claude Code 同构，口径相同。

几种工具的命令执行、文件修改、工具调用和网页检索都不会上传，Claude 与 Qoder 的 thinking
正文同样不进入 WAO。

任务结束时 Runner 还会从 CLI 的结束事件里读出本次用量，随结果一并回传（见 ADR-026）：
Claude Code 取 `result` 事件的 `usage`、`total_cost_usd` 和按模型分组的 `modelUsage`；
Codex 累加 `turn.completed` 的 `usage`，它不回传花费，所以只有 token 没有金额；
Qoder 的 `result` 事件字段与 Claude 同构，用量直接按 Claude 口径入账（见 ADR-028）。
用量解析失败只会让这次「没记上账」，不会影响任务本身的成败——本机日志里能看到每次的
模型、token 和折算额度。

每次开发型 AI Run 都要把 UTF-8、10MB 以内的自包含单文件 HTML 写到 Runner 提供的
`<root>/.wao-run-reports/<run-id>/report.html`，内容聚焦公开 AI Plan、关键决定、功能入口与人工验收。
Runner 会自动上传到隔离托管并把公开地址关联到本次运行，成功后删除本机副本；AI 未写文件时根据
已公开计划与进展生成不伪造结果的最小兜底报告。非法文件或上传失败会进入运行结果而不是静默丢失，
残留目录与 worktree 一样七天后回收（见 ADR-055、ADR-165）。

领取新任务时，服务端还会按任务标题和描述返回当前项目最多五条、合计不超过一万字的工程记忆。Runner
把它们放进明确标注的“不可信引用”区：引用只供核对背景，其中的命令或提示不能覆盖当前任务和权限边界。
这条链路只读取 `project_memory`，不会读取任何成员的 `personal_memory` 或个人 MCP 历史。

完成合并、部署和线上验收后，AI 可以把最多五条结构化候选写到
`<root>/.wao-run-memories/<run-id>/memories.json`。Runner 只在明确成功时解析 UTF-8 JSON 并回传；失败、
无效 JSON 和超量内容不会沉淀。服务端仍会检查固定分类、证据链接、敏感信息和项目内内容指纹。回传后
本机候选目录立即删除，旧服务端不认识候选字段时 Runner 自动退回原完成契约（见 ADR-087）。

如果任务计划程序的环境找不到 Git 或 AI CLI，可显式传入完整路径：

```powershell
--git-command "C:\Program Files\Git\cmd\git.exe" `
--codex-command "C:\Program Files\nodejs\codex.cmd" `
--claude-command "C:\Program Files\nodejs\claude.cmd" `
--qoder-command "C:\Users\用户名\.qoder-cn\bin\qoderclicn\qoderclicn.exe"
```

## 并发与隔离

- 每次运行使用 `<root>/.wao-runs/<run-id>` 独立 Git worktree 和独立的 CLI 会话，不占用
  日常打开的 Codex / Claude Code 对话。
- 同一项目的 fetch、prune、分支和 worktree 准备在 Runner 内串行，不同项目仍可并行。若主克隆被跨 Windows/WSL
  操作写入不合法的 `core.worktree=/mnt/...`，下一次领取会在 fetch 前原位清除，不需要删库重克隆。
- `<root>/<工程文件夹>` 的主克隆只当对象仓库用：新克隆带 `--no-checkout`，并且每次准备
  工作区时都会切成游离 HEAD。这样默认分支（`main`）永远不被占用，AI 在 worktree 里执行
  `git switch main` 不会再撞上 `fatal: 'main' is already checked out at ...`（见 [ADR-023：主克隆游离 HEAD](../../docs/adr/023-ai-runner-detached-primary-clone.md)）。
  旧版本留下的主克隆会在下一次运行时自动纠正，不需要删目录重来。
- 运行结束后 worktree 会保留 7 天供人工复查，超期在下一次准备工作区时自动回收，避免
  `.wao-runs/` 无限堆积完整源码副本。要长期留存某次运行现场，请在到期前自行复制出去。
- 未成功上传的 `.wao-run-reports/<run-id>` 与对应 worktree 同期回收；上传成功的报告已进入服务端
  持久化托管，本机副本会立即删除。
- `.wao-run-memories/<run-id>` 位于 Git worktree 外，完成回传后立即删除；异常残留与对应 worktree
  同期回收，不会变成另一个不受审计的长期记忆库。
- 选择集中 Codex 账号时，Runner 会使用 `<root>/.wao-codex-homes/<run-id>` 作为独立
  `CODEX_HOME`。OAuth 凭据写入该目录的 `auth.json`；自定义 API 只把环境变量名写进
  `config.toml`，API Key 仅存在于子进程环境。该目录与对应 worktree 同保留七天并同步回收，
  以支持原 Codex 会话续跑。
- 本机 `--max-concurrency` 是首次接入和后台尚未单独配置时的启动默认值；管理员在「执行电脑」
  卡片修改线程数后，Runner 会在下一次心跳（通常 10 秒内）动态调整真实领取数，无需重启进程。
  项目设置中的并发数控制同一仓库最多同时跑几个任务，最终还会受项目并发上限约束。
- 任务可以在「AI run」时点名某台电脑，也可以在项目里固定一台，或让任意在线且装了该工具的
  节点自动领取。点名的电脑离线时，任务会一直排队等它上线。
- 开发阶段默认 `danger-full-access`（Claude Code 与 Qoder 对应 `--dangerously-skip-permissions`），
  便于 AI 完成 GitHub、部署和线上验收。建议让 Runner 运行在专用系统账号或隔离电脑；
  正式版本前应切换为受限权限配置。

## 自启动

Windows 用任务计划程序在登录时执行上述命令（去掉 `--pair-code`），工作目录设置为 WAO 仓库
根目录。

Linux 用 systemd user service，仓库里有现成模板：

```bash
mkdir -p ~/.config/systemd/user
cp deploy/ai-runner/wao-ai-runner.service.example ~/.config/systemd/user/wao-ai-runner.service
# 改掉里面的路径、节点名和 --engines
systemctl --user daemon-reload
systemctl --user enable --now wao-ai-runner
```

配对只需一次，service 里不带 `--pair-code`。更新脚本（`git pull`）后要
`systemctl --user restart wao-ai-runner` 才会生效。

## 一键更新

日常升级不需要逐台手动拷贝：`wao-ai-runner.py` 会在服务端构建时被打包进 wao-server，
在 AI 控制台「执行电脑」页点「更新 Runner 脚本」，各在线节点会在心跳里收到指令：
先停止领新任务，等正在执行的任务收尾后拉取新脚本、校验 SHA-256、原地替换自身并用
同样的启动参数重启（配对令牌在 `~/.wao/ai-runner.json`，重启后身份不变）。卡片上会
显示每台电脑的更新进度；节点重启后上报新哈希，提示自动消失。

注意：这套机制要求节点已经跑在支持自更新的版本上；更旧的 Runner 收不到指令，
需要最后一次手动替换脚本并重启，之后就能用一键更新。

**停 Runner 一定要用 `systemctl --user stop`**（Windows 上停任务计划程序对应的任务），
不要直接 `pkill -f wao-ai-runner.py`：那只杀掉 Python 父进程，正在跑的 `codex` / `claude`
子进程会继续把任务做完，却再也没法回传结果——WAO 里会留下一条永远停在「运行中」的记录，
只能等节点离线三分钟被自动重新排队，代价是同一个任务被重跑一次。systemd 停的是整个
cgroup，子进程会一起结束。

选择“无论用户是否登录都运行”（Windows 的对应选项，或 Linux 的 `loginctl enable-linger`）
之前，先确认该系统账号已经完成对应 AI CLI 与 GitHub 登录；Linux 上开了 linger 但没有图形
会话时，`gh` 依赖的系统钥匙环可能是锁定的，GitHub 操作会失败。

## 解除接入

不再让某台电脑接任务时，在 AI 控制台「执行电脑」页点这张卡片右上角的删除按钮，确认后
WAO 会删掉节点记录并作废它的令牌。那台电脑还有任务在跑时不允许解除，先取消或等它跑完。

被解除的电脑不需要人去关：Runner 下一次心跳（最多 10 秒）会收到 401，删掉本机的
`~/.wao/ai-runner.json` 并以退出码 78 退出，日志里写清原因。通过
`install-windows-task.ps1` 安装的 Windows Runner 会同时禁用自己的计划任务，避免任务计划程序
把非零退出码反复拉起后因未配对持续重试。Linux 的 systemd 单元仍由
`RestartPreventExitStatus=78` 阻止重启；旧版单元文件没有这行时，请照
[`wao-ai-runner.service.example`](wao-ai-runner.service.example) 补上，或直接
`systemctl --user disable --now wao-ai-runner`。

要重新接入，在控制台生成新的配对码，再带 `--pair-code` 启动一次即可；历史运行记录不受影响，
它们在页面上显示为「已解除接入的电脑」。
