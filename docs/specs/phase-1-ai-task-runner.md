# Phase 1：任务驱动的 AI Run

## 用户路径

1. 项目管理员打开“项目设置 → AI Run”，填写 Git 仓库、默认分支、本机工程文件夹，
   设置项目并发数和首选电脑后开启开关。
2. 新电脑点击“生成电脑配对码”，按 `deploy/ai-runner/README.md` 启动 Runner；配对码
   十分钟有效且只能使用一次。
3. 单个任务在详情页点击“AI run run”；多个任务在项目列表勾选后批量点击。
4. 首页立即显示排队/运行数量、在线电脑与槽位。运行卡片持续显示最近一条 AI 反馈和更新时间，
   点击先查看实时执行记录，再按需进入对应任务。
5. Runner 自动准备 worktree、调用 Codex 并流式回传用户可读摘要；成功后任务进入“待验证”。
6. 每次运行的摘要时间线永久关联到任务详情，完成后仍可回看 AI 做了什么、结论和失败原因。

## 验收标准

- 项目未开启或未填写仓库时，入队返回可理解的 400 错误，不生成半条运行记录。
- 同一个任务在 queued/claimed/running 状态下重复点击不会重复入队。
- 一台电脑同时运行数不超过 Runner 配置；同一项目不超过项目配置。
- 首选电脑离线时任务保持排队，不会错误派给其他电脑；自动分配模式可被任意在线节点领取。
- 每次运行的分支、worktree 和 Codex 会话独立。
- 节点离线超过三分钟后任务重新排队；成功/失败均有结果摘要和结束时间。
- 前端每五秒刷新运行控制台；运行卡片展示最近反馈及新鲜度，点击先打开进展时间线，详情按钮
  可准确跳转到 `/projects/{projectId}?task={taskId}`。
- 任务详情按运行批次展示最近 30 次 AI 档案，每次最多保留最近 50 条摘要；活动运行持续轮询，
  历史运行可展开查看最终结果。
- 最近完成列表直接展示失败摘要；点击运行记录可查看失败阶段、完整错误、退出码、执行电脑和
  工作目录，再从详情进入对应任务。
- Runner 将不含凭据的诊断写入本机 `~/.wao/ai-runner.log`，并在启动时校验 Git 与 Codex
  命令，任务计划程序环境异常时可通过完整命令路径修复。
- Windows Runner 必须通过标准输入传递完整多行提示，保证任务 Markdown 地址不会经过
  `.cmd` 转发脚本时被截断；Codex 仅退出码为 0 仍不足以判成功，还必须返回明确完成标记。
- Runner 使用 `codex exec --json` 的 JSONL 事件流，只保存 agent message、公开的 reasoning summary、
  plan update 与阶段/结果；command execution、file change、MCP tool call、web search 不进入 WAO。
- Runner 从本机 Git credential helper 读取现有 GitHub HTTPS 凭据，仅通过子进程 `GH_TOKEN`
  提供给 Codex，并自动补齐 Windows GitHub CLI 路径；不持久化或记录令牌。Codex 应优先使用
  `gh` 完成 PR、合并和部署跟踪，连接器不可用不能直接判定任务阻塞。

## 安全边界

- 配对码和节点令牌不写日志、不进 Git；节点令牌只保存于 `~/.wao/ai-runner.json`。
- WAO 不接收 Codex、OpenAI 或 GitHub 凭据。
- Runner 协议仅开放配对、心跳、领取、进展、完成五类接口；业务配置、任务档案与运行列表仍要求
  WAO 会话。单条进展最多 1000 字，服务端校验运行归属并拒绝未知类型。
