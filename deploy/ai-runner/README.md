# WAO AI Runner

AI Runner 安装在娃哥、娃弟的 Windows 电脑和 Linux 主节点上。WAO 只负责排队和展示运行
状态；Codex / Claude Code 账号、GitHub 登录态和源码始终保留在本机。

一台电脑可以同时装两种 AI CLI，Runner 启动时会自动探测本机装了哪些，并把结果作为「本机
能力」上报；WAO 只会把对应工具的任务派给它。

## 首次接入

1. 确认本机已有 Python 3.10+、Git，以及至少一种已登录的 AI CLI：
   - Codex CLI，执行过 `codex login`；
   - Claude Code CLI，执行过 `claude` 并完成登录。
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

`--engines` 留空表示自动探测（装了哪种就报哪种）；显式指定时找不到对应命令会直接启动失败，
避免电脑上报了自己其实跑不了的能力。需要固定模型时加 `--claude-model opus`。

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
启动时 Runner 会通过 `git credential fill` 读取本机已有的 GitHub HTTPS 凭据，并仅在内存中以
`GH_TOKEN` 传给 AI CLI 子进程，同时自动补齐 Windows 的 GitHub CLI 安装目录。凭据不会写入
Runner 状态、日志或 Git；因此只需让这台电脑的 Git 凭据管理器能正常 `git push`，自动任务
就可以继续创建 PR、合并和跟踪部署，不依赖 AI 工具内置的 GitHub 连接器。

运行期间 Runner 消费流式事件把用户可读进展实时回传首页与任务详情：

- Codex：`codex exec --json` 的 agent message、公开推理摘要、plan update 与阶段/结果。
- Claude Code：`claude -p --output-format stream-json` 的 assistant 文本与 TodoWrite 计划。

两种工具的命令执行、文件修改、工具调用和网页检索都不会上传，Claude 的 thinking 正文同样
不进入 WAO。

如果任务计划程序的环境找不到 Git 或 AI CLI，可显式传入完整路径：

```powershell
--git-command "C:\Program Files\Git\cmd\git.exe" `
--codex-command "C:\Program Files\nodejs\codex.cmd" `
--claude-command "C:\Program Files\nodejs\claude.cmd"
```

## 并发与隔离

- 每次运行使用 `<root>/.wao-runs/<run-id>` 独立 Git worktree 和独立的 CLI 会话，不占用
  日常打开的 Codex / Claude Code 对话。
- 本机 `--max-concurrency` 控制这台电脑最多同时跑几个任务；项目设置中的并发数
  控制同一仓库最多同时跑几个任务，两层限制取更严格者。
- 任务可以在「AI run」时点名某台电脑，也可以在项目里固定一台，或让任意在线且装了该工具的
  节点自动领取。点名的电脑离线时，任务会一直排队等它上线。
- 开发阶段默认 `danger-full-access`（Claude Code 对应 `--dangerously-skip-permissions`），
  便于 AI 完成 GitHub、部署和线上验收。建议让 Runner 运行在专用系统账号或隔离电脑；
  正式版本前应切换为受限权限配置。

## 自启动

Windows 可用任务计划程序在登录时执行上述命令（去掉 `--pair-code`）；Linux 用 systemd
user service 或 `nohup` 常驻。工作目录设置为 WAO 仓库根目录，并选择“无论用户是否登录都
运行”前，先确认该系统账号已经完成对应 AI CLI 与 GitHub 登录。
