# WAO AI Runner

AI Runner 安装在娃哥、娃弟的 Windows 电脑和 Linux 主节点上。WAO 只负责排队和展示运行
状态；Codex / Claude Code / Qoder 账号、GitHub 登录态和源码始终保留在本机。

一台电脑可以同时装多种 AI CLI，Runner 启动时会自动探测本机装了哪些，并把结果作为「本机
能力」上报；WAO 只会把对应工具的任务派给它。

## 首次接入

1. 确认本机已有 Python 3.10+、Git，以及至少一种已登录的 AI CLI：
   - Codex CLI，执行过 `codex login`；
   - Claude Code CLI，执行过 `claude` 并完成登录；
   - Qoder CLI（`qodercli`），执行过 `qodercli` 并在 TUI 里 `/login` 完成登录。
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

`--engines` 留空表示自动探测（装了哪种就报哪种，可选 codex、claude、qoder）；显式指定时
找不到对应命令会直接启动失败，避免电脑上报了自己其实跑不了的能力。需要固定模型时加
`--claude-model opus`。Qoder 的引擎 id 是 `qoder`，命令名是 `qodercli`，自动探测按命令名找。

项目里只填一个 HTTPS 仓库地址，但每台电脑的 Git 认证方式可以不同：`--git-protocol` 默认
`auto`，本机有 HTTPS 凭据就用 HTTPS，没有（例如只配了 SSH key 的 Linux 主节点）就自动把
地址换成 `git@github.com:组织/仓库.git`。想固定行为可以显式传 `--git-protocol ssh` 或
`https`。切换协议后已经克隆过的仓库会自动更新 `origin`，不需要手工删目录。

配对成功后，节点令牌只写入本机 `~/.wao/ai-runner.json`，不会进入项目或 Git。
以后启动时不需要再次输入配对码。

Runner 会把启动信息、Git 与各 AI CLI 的命令路径和任务失败阶段写入 `~/.wao/ai-runner.log`。
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
Qoder 按 Credits 配额计费，结束事件没有按次用量字段时只记模型不记账（见 ADR-028）。
用量解析失败只会让这次「没记上账」，不会影响任务本身的成败——本机日志里能看到每次的
模型、token 和折算额度。

如果任务计划程序的环境找不到 Git 或 AI CLI，可显式传入完整路径：

```powershell
--git-command "C:\Program Files\Git\cmd\git.exe" `
--codex-command "C:\Program Files\nodejs\codex.cmd" `
--claude-command "C:\Program Files\nodejs\claude.cmd" `
--qoder-command "C:\Users\用户名\.qoder-cli\bin\qodercli.exe"
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
- 本机 `--max-concurrency` 控制这台电脑最多同时跑几个任务；项目设置中的并发数
  控制同一仓库最多同时跑几个任务，两层限制取更严格者。
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
