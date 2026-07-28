# ADR-031：AI 账号改用 OAuth 登录，额度接真实接口

## 状态

已采纳。补充 ADR-030，不改变「凭据加密落库 + Runner 按需拉取」的基本结构。

## 背景

ADR-030 把 AI 账号集中到了服务端，但落地后有两处和需求对不上：

1. **登录方式不对。** 需求原文指着 CPAMC 的截图说「这个项目的 UI 和登录方式是我想要的效果」，
   截图上是一排「开始 Codex 登录」「开始 Anthropic 登录」按钮。而 ADR-030 只提供了一个
   多行文本框，要用户自己去某台电脑上找到 `~/.claude/.credentials.json` 再整段粘贴进来。
   这既不是需求要的交互，也把「统一管理」变成了「换个地方手抄凭据」——凭据过期后还得再抄一遍。
2. **额度查不到。** ADR-030 规定 Claude 只在配置了 `wao.ai.claude.usage-url` 时才查额度，
   而这个配置项没有默认值，也从没被配过，因此实际行为恒为 `NO_API`。需求目标 2
   「想要可以在系统上直接查看每个 AI 账号的额度余额」等于没有实现。

第二条的根因是当时认为「没有可靠的程序化余额接口」。实测下来这个判断对 Codex / Qoder 成立，
但对 Claude 不成立：Claude Code CLI 自己就在用 `https://api.anthropic.com/api/oauth/usage`，
用 OAuth access token 直接可以取到分窗口的用量百分比、重置时间和额外用量消耗。

## 决策

- **补上 OAuth 登录（授权码 + PKCE）。** 用 Claude Code CLI 那套公开客户端参数，
  服务端不需要任何 Anthropic 侧密钥：
  - `POST /api/settings/ai-accounts/login/start` 生成 `state` 与 `code_verifier`，
    返回授权地址；`code_verifier` 只落 `ai_account_oauth_session` 表，**不下发前端**。
  - 用户在浏览器完成授权后把回调页给出的授权码粘回来，
    `POST /api/settings/ai-accounts/login/complete` 在服务端换取令牌并建账号。
  - 会话十分钟过期，换到令牌或过期后即删。
- **不采用本地回环端口（`http://localhost:1455/callback`）那种回调方式。** 浏览器在用户自己的
  电脑上，而 WAO 跑在 Linux 主节点，回环地址根本连不通。粘贴授权码这一步换来的是
  「在任何一台机器的浏览器里都能完成登录」，这正是需求要解决的问题。
- **额度改调真实接口。** 同步时用 `/api/oauth/profile` 取邮箱与套餐，`/api/oauth/usage`
  取用量，落成结构化字段（`quota_window` / `quota_percent` / `quota_resets_at`）而不只是一坨原始
  JSON。页面展示「最紧张的那条限额」——用户关心的是还能不能接着跑，不是六个百分比。
  原始响应仍整份留在 `quota_raw_json` 里备查。
- **`wao.ai.claude.usage-url` 配置项废弃**，改为内置默认地址；所有第三方端点
  （authorize / token / api-base / client-id / scopes）都可通过 `wao.ai.claude.*` 覆盖：
  它们是别人家的地址，改路径时不该要求我们改代码重新发版。
- **令牌到期自动续期。** access token 剩余不足 5 分钟就先用 refresh token 换新的再用，
  换完立刻加密回写。下发给 Runner 前也走同一条路径，避免 Runner 拿着刚过期的令牌去跑任务。
  Anthropic 不一定每次都换新的 refresh token，没换就继续用旧的。
- **续期失败不阻断下发。** 某个账号续不了期时，`/api/ai/runner/accounts` 仍返回它已存的凭据，
  只是不再新鲜。这一步的定位是「尽量把新鲜的给出去」，不是准入校验：一个账号出问题不该连累
  同一批的其他账号，真过期了让任务失败时报出来，比在这里静默拦截更容易排查。
- **额度查不到就说查不到。** `quota_percent` 为 null 时前端显示「未获取」和原因，不画成 0%。
  把没查到当成没用掉，会让人误判还能跑多少任务。Codex / Qoder 维持 ADR-030 的 `NO_API`。

## 后果

- 加一个 Claude 账号从「去某台电脑翻出凭据文件再粘过来」变成「点登录 → 授权 → 粘一次授权码」，
  且凭据过期后由服务端自动续期，不需要人再介入。
- 页面上能直接看到每个 Claude 账号的套餐、邮箱和当前最紧张的额度窗口用了多少、什么时候重置。
- 服务端多了一次对 Anthropic 的出站依赖。同步失败只影响额度展示，不影响账号可用性，
  也不影响 Runner 取凭据。
- **Codex / ChatGPT 与 Qoder 仍只能粘贴凭据。** Codex 的 OAuth 流程可以按同样的框架接，
  但本次没有可用于实测的账号，未经真实链路验证的第三方登录代码不进主干；留待单独立项。
- 需求原文的 1.4「其他 CPAMC 提供的 Token 方式」和 1.5「现在主流的 API 协议」**本次明确不做**。
  这两条的前提是 WAO 对外提供一个 `/v1/messages` 之类的兼容端点，也就是变成一个 API 网关；
  而当前形态下 WAO 管的是各 CLI 的**登录态**，执行体是 Runner 拉起的 `claude` / `codex`
  进程，它们直接读本机凭据文件，中间不需要协议翻译层。需要网关能力时另开 ADR。
