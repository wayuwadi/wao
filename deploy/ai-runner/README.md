# WAO AI Runner

AI Runner 安装在娃哥、娃弟的 Windows 电脑和 Linux 主节点上。WAO 负责排队、展示运行状态，
并可按单次运行下发用户主动集中管理的 Codex / Claude Code 凭据或自定义 Responses API；
未选择集中账号时仍使用本机登录态。GitHub 登录态和源码始终保留在 Runner 本机。

一台电脑可以同时装多种 AI CLI，Runner 启动时会自动探测本机装了哪些，并把结果作为「本机
能力」上报；WAO 只会把对应工具的任务派给它。

## 首次接入

1. 确认本机已有 Python 3.10+、Git，以及至少一种 AI CLI。使用执行电脑本地登录态时需提前登录；
   选择 WAO 集中账号时 CLI 本身仍必须安装，但不要求本机另做登录：
   - Codex CLI，执行过 `codex login`；
   - Claude Code CLI，执行过 `claude` 并完成登录；
   - Qoder CLI（国际版命令名 `qodercli`，国内版 `qoderclicn`），执行过一次并在 TUI 里 `/login` 完成登录。
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

`--engines` 留空表示自动探测（装了哪种就报哪种，可选 codex、claude、qoder）；显式指定时
找不到对应命令会直接启动失败，避免电脑上报了自己其实跑不了的能力。需要固定模型时加
`--claude-model opus`。Qoder 的引擎 id 是 `qoder`，命令名国际版叫 `qodercli`、国内版叫
`qoderclicn`，自动探测按顺序两个名字都认。

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
当 AI 以 BLOCKED 结束且保留会话 id 时，任务详情可直接补充信息；Runner 会把同一条运行固定派回
原电脑，在原 worktree 中恢复同一个 Codex 或 Claude Code 会话，而不是创建新会话。
启动时 Runner 会通过 `git credential fill` 读取本机已有的 GitHub HTTPS 凭据，并仅在内存中以
`GH_TOKEN` 传给 AI CLI 子进程，同时自动补齐 Windows 的 GitHub CLI 安装目录。凭据不会写入
Runner 状态、日志或 Git；因此只需让这台电脑的 Git 凭据管理器能正常 `git push`，自动任务
就可以继续创建 PR、合并和跟踪部署，不依赖 AI 工具内置的 GitHub 连接器。

运行期间 Runner 消费流式事件把用户可读进展实时回传首页与任务详情：

- Codex：`codex exec --json` 的 agent message、公开推理摘要、plan update 与阶段/结果。
- Claude Code：`claude -p --output-format stream-json` 的 assistant 文本与 TodoWrite 计划。
- Qoder：`qodercli -p --output-format stream-json`，事件与 Claude Code 同构，口径相同。

几种工具的命令执行、文件修改、工具调用和网页检索都不会上传，Claude 与 Qoder 的 thinking
正文同样不进入 WAO。

任务结束时 Runner 还会从 CLI 的结束事件里读出本次用量，随结果一并回传（见 ADR-026）：
Claude Code 取 `result` 事件的 `usage`、`total_cost_usd` 和按模型分组的 `modelUsage`；
Codex 累加 `turn.completed` 的 `usage`，它不回传花费，所以只有 token 没有金额；
Qoder 的 `result` 事件字段与 Claude 同构，用量直接按 Claude 口径入账（见 ADR-028）。
用量解析失败只会让这次「没记上账」，不会影响任务本身的成败——本机日志里能看到每次的
模型、token 和折算额度。

任务包含值得长期查阅或分享的分析、调研、方案、决策依据或验收证据时，AI 可以把 UTF-8、10MB
以内的自包含单文件 HTML 写到 Runner 提供的 `<root>/.wao-run-reports/<run-id>/report.html`。
Runner 会自动上传到隔离托管并把公开地址关联到本次运行，成功后删除本机副本；普通小改动不会
强制生成占位报告。上传失败会进入运行结果而不是静默丢失，残留目录与 worktree 一样七天后回收
（见 ADR-055）。

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
- `<root>/<工程文件夹>` 的主克隆只当对象仓库用：新克隆带 `--no-checkout`，并且每次准备
  工作区时都会切成游离 HEAD。这样默认分支（`main`）永远不被占用，AI 在 worktree 里执行
  `git switch main` 不会再撞上 `fatal: 'main' is already checked out at ...`（见 ADR-023）。
  旧版本留下的主克隆会在下一次运行时自动纠正，不需要删目录重来。
- 运行结束后 worktree 会保留 7 天供人工复查，超期在下一次准备工作区时自动回收，避免
  `.wao-runs/` 无限堆积完整源码副本。要长期留存某次运行现场，请在到期前自行复制出去。
- 未成功上传的 `.wao-run-reports/<run-id>` 与对应 worktree 同期回收；上传成功的报告已进入服务端
  持久化托管，本机副本会立即删除。
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
