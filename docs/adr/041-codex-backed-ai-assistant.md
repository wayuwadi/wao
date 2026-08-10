# ADR-041：WAO AI 助手复用 Linux Codex Runner

## 状态

已采纳。补充 ADR-021、ADR-030～034。

## 背景

WAO 页面已有 AI 助手入口，但服务端仍调用一套单独的 Chat Completions 配置。生产 Compose
没有注入该配置，页面只能返回“尚未配置”；即使补一把全局 API Key，也会与设置页已经集中管理的
Codex OAuth / Responses API 账号形成两套凭据，且无法复用 Linux 主节点的 VPN 出口和执行能力。

聊天问答与开发任务的权限边界也不同：问答只应读取用户主动提交的文字，不应获得仓库写权限；开发任务
则需要在独立 worktree 中修改、提交、部署和验收。

## 决策

- CD 在 GitHub Actions 构建机从 OpenAI 官方发布域名下载并校验固定版 Linux x86_64 Codex 独立包，
  再通过 SSH 随发布下发；主节点本地幂等安装并校验版本，不依赖其受限的公网下载出口。
- 已配对的 Linux Runner 额外上报 `codex` 能力，并开启集中 AI 账号同步。没有配对令牌时只安装 CLI，
  不伪造节点身份或自动生成长期令牌。
- AI 助手请求写入短期 `ai_assistant_run` 队列；仅平台为 Linux 且上报 `codex` 的 Runner 可以领取。
- Runner 优先领取开发任务，空闲槽位才领取聊天，避免连续问答饿死任务队列。
- 聊天优先使用设置页第一个启用的 Codex 账号；OAuth 与自定义 Responses API 都沿用现有的单次运行
  隔离 `CODEX_HOME` 机制。没有集中账号时才使用 Runner 本机登录态。
- 每次聊天在临时空 Git 仓库中执行 `codex exec --json --sandbox read-only`；提示词明确禁止执行命令、
  修改文件和泄露凭据。结束后删除临时目录，任务执行仍保持原有 `danger-full-access` 策略。
- 页面历史仍只保存在浏览器当前会话；服务端排队记录完成后清除请求正文，仅保留最多 30,000 字结果用于
  当前请求取回，七天后可清理。每位成员同一时间只允许一个未结束的聊天。
- `/api/assistant` 与现有 AI API 一样固定路由到 Linux VPN 主节点；主节点或 Codex Runner 离线时页面
  显示明确原因和重试入口，不把静态欢迎语伪装成已接通 AI。

## 后果

- 页面聊天和任务执行共用同一套 Codex 账号与 Linux 出口，不再需要额外生产 API Key。
- 聊天会占用 Runner 槽位，但开发任务优先；高并发聊天不是本阶段目标。
- Linux Runner 尚未配对时仍需管理员在 WAO AI 控制台生成一次性配对码，这是既有安全边界，部署脚本
  不绕过它。
