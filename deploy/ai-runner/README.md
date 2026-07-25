# WAO AI Runner

AI Runner 安装在娃哥、娃弟各自的电脑上。WAO 只负责排队和展示运行状态；Codex
账号、GitHub 登录态和源码始终保留在本机。

## 首次接入

1. 确认本机已有 Python 3.10+、Git、Codex CLI，并执行过 `codex login`。
2. 打开 WAO 项目设置 → AI Run，填写仓库地址并开启功能。
3. 点击“生成电脑配对码”，十分钟内在对应电脑运行：

```powershell
python .\deploy\ai-runner\wao-ai-runner.py `
  --server https://wao.wayuwa.cn `
  --pair-code 页面显示的配对码 `
  --name 娃哥电脑 `
  --root D:\CodeSpace `
  --max-concurrency 2
```

配对成功后，节点令牌只写入本机 `~/.wao/ai-runner.json`，不会进入项目或 Git。
以后启动时不需要再次输入配对码。

Runner 会把启动信息、Git/Codex 命令路径和任务失败阶段写入
`~/.wao/ai-runner.log`。页面“最近完成”中的失败记录会直接显示错误摘要，点击记录可查看
完整失败原因、退出码和工作目录。日志达到 2 MiB 后自动轮换，不记录配对码和节点令牌。
任务提示通过标准输入交给 Codex，避免 Windows 的 `.cmd` 转发脚本截断包含链接的多行提示；
Runner 还会校验 Codex 最终答复中的完成状态，不再把反问、等待补充信息误判为成功。

如果任务计划程序的环境找不到 Git 或 Codex，可显式传入完整路径：

```powershell
--git-command "C:\Program Files\Git\cmd\git.exe" `
--codex-command "C:\Program Files\nodejs\codex.cmd"
```

## 并发与隔离

- 每次运行使用 `<root>/.wao-runs/<run-id>` 独立 Git worktree 和独立
  `codex exec` 会话，不占用日常打开的 Codex 对话。
- 本机 `--max-concurrency` 控制这台电脑最多同时跑几个任务；项目设置中的并发数
  控制同一仓库最多同时跑几个任务，两层限制取更严格者。
- 项目可固定到某一台电脑，也可选择“自动分配”，由当前在线且有空闲槽位的节点领取。
- 开发阶段默认 `danger-full-access`，便于 Codex 完成 GitHub、部署和线上验收。
  建议让 Runner 运行在专用 Windows 用户或隔离电脑；正式版本前应切换为受限权限配置。

## 自启动

可用 Windows 任务计划程序在登录时执行上述命令（去掉 `--pair-code`）。工作目录设置为
WAO 仓库根目录，并选择“无论用户是否登录都运行”前，先确认该系统账号已经完成
Codex 与 GitHub 登录。
