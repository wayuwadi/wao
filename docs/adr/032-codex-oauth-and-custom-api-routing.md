# ADR-032：Codex OAuth 与自定义 API 按运行路由

## 状态

已采纳。补充 ADR-030、ADR-031，不改变「服务端加密保存凭据、Runner 按需执行本地 CLI」的总体结构。

## 背景

AI 账号页目前虽然能手工保存 Codex 的 `~/.codex/auth.json`，但一键 OAuth 只有 Claude Code，
页面也只是表单和文字列表。多个同类账号开启 Runner 定时同步后还会依次覆盖同一个本地凭据文件，
无法保证一次任务究竟使用哪个账号。

用户还需要接入 CLIProxyAPI 提供的 OpenAI Responses 兼容端点。它不是一份 Codex 登录文件，
而是 `Base URL + API Key + 模型` 组成的模型供应商配置；只把 API Key 写进 `auth.json` 不能让
Codex 改走自定义端点。

## 决策

- **Codex 增加 OAuth PKCE 登录。** 使用 Codex CLI 当前公开客户端参数：
  `https://auth.openai.com/oauth/authorize`、`https://auth.openai.com/oauth/token`、
  client id `app_EMoamEEZ73f0CkXaXp7hrann` 和回调 `http://localhost:1455/auth/callback`。
  浏览器和 WAO 服务端不在同一台机器，因此不在服务端监听 1455 端口；授权完成后即使浏览器显示
  无法连接，地址栏仍包含 `code` 与 `state`，用户把完整回调地址粘回 WAO 完成交换。
- **严格生成 Codex 官方凭据格式。** 下发给 Codex CLI 的 JSON 包含 `auth_mode: chatgpt`、
  `OPENAI_API_KEY: null`、`tokens.id_token/access_token/refresh_token/account_id` 与
  `last_refresh`。服务端从 ID token 解析邮箱、套餐和账号 id，并在 access token 临近过期时续期。
- **自定义 API 作为 Codex 账号的一种认证方式。** `auth_method=api_key` 时保存 `base_url`、
  `model`、`api_protocol=responses`；API Key 仍写入既有 `credential_json` 并用 AES-256-GCM 加密，
  列表接口不返回明文。连接测试调用规范化后的 `<base_url>/models`，只报告状态和模型数量。
- **账号选择固化到一次运行。** `ai_task_run.ai_account_id` 保存用户启动任务时选择的账号。
  账号必须启用且 provider 与 engine 一致；重跑和续跑沿用原账号。未选择时继续使用 Runner 本地登录态，
  兼容现有节点。选择集中账号且未手动指定电脑时，默认固定到支持对应 CLI 的 Linux 主节点；
  Linux 尚未上报该工具时明确阻止启动，不静默落到使用个人本地环境的 Windows 电脑。用户仍可显式改选其他电脑。
- **敏感配置只随领取结果发给本次 Runner。** 已配对 Runner 领取任务后，服务端才解密该运行选中的
  凭据。Runner 为这次 Codex 子进程创建临时 `CODEX_HOME`：OAuth 写 `auth.json`；自定义 API 写
  `config.toml` 并通过专用环境变量注入 API Key。该目录与运行 worktree 一起保留七天，以便原
  Codex 会话续跑；超期时同步回收。不覆盖用户全局配置，并发运行也不会互相切换账号。
- **页面改为可视化管理台。** OAuth 入口使用独立卡片；账号区提供 provider/状态筛选、搜索、网格卡片、
  额度或连接状态、启停、编辑和删除。卡片明确提供“查看额度”和“刷新额度”：查看时展开套餐、凭据有效期、
  全部可解析限额窗口和重置时间；刷新复用服务端真实同步接口。没有可靠额度 API 时只说明原因，不显示
  伪造的 0%；自定义 API 对应显示“查看状态”和“测试连接”。加载、空数据、保存中、刷新中、失败重试及
  窄屏单列均有明确状态。

## 后果

- Claude Code 与 Codex 都可从 WAO 发起 OAuth；Codex 首次授权需要复制一次浏览器回调地址。
- CLIProxyAPI 或其他 Responses 兼容端点可以像账号一样保存、测试，并在单次 Codex 运行中选择。
- 账号不再靠覆盖全局文件来决定一次任务的身份，历史运行也能追溯当时选中的账号 id。
- 自定义端点是否完整兼容 Codex 所需的 Responses 工具调用仍由连接后的真实运行验证；`/models`
  通过只表示网络和鉴权可用，不伪装成完整执行已验证。
