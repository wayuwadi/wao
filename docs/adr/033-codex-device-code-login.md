# ADR-033：Codex 改用设备码登录

## 状态

已采纳。替代 ADR-032 中「把 `localhost:1455` 失败页地址复制回 WAO」的 Codex 登录流程；
自定义 API、账号路由、凭据格式和额度查询决策不变。

## 背景

ADR-032 复用了 Codex CLI 浏览器登录的固定回调 `http://localhost:1455/auth/callback`。Codex CLI
会先在运行它的机器监听 1455 端口，但 WAO 的浏览器在用户 Windows 电脑、服务端在 Linux 主节点，
用户电脑没有本地监听器。OpenAI 授权成功后浏览器因此显示 `ERR_CONNECTION_REFUSED`，还必须让用户
复制失败页的完整地址回到 WAO。这不是可接受的一键登录体验，也无法由网页跨域读取另一个标签页补救。

Codex 官方同时为远程和无头环境提供设备码登录：申请一次性短码、让用户在 OpenAI 官方页面确认，
再由发起登录的服务轮询授权结果。该流程不需要任何浏览器回调到用户电脑。

## 决策

- Codex 登录调用 `POST https://auth.openai.com/api/accounts/deviceauth/usercode` 申请
  `device_auth_id`、`user_code` 和轮询间隔，浏览器只打开 `https://auth.openai.com/codex/device`。
- WAO 页面展示一次性短码并自动轮询自己的登录状态接口；同时提供“复制短码”“重新打开授权页”和
  “我已完成，立即检查”按钮。页面不再展示 localhost 地址输入框，也不要求用户复制失败页面。
- WAO 服务端按 OpenAI 返回的最小间隔调用 `/api/accounts/deviceauth/token`。403/404 只表示仍在
  等待用户确认；成功响应中的 `authorization_code` 与 `code_verifier` 用
  `https://auth.openai.com/deviceauth/callback` 换取令牌。
- 设备码会话十五分钟过期。`device_auth_id`、短码、轮询间隔和最近轮询时间只暂存在
  `ai_account_oauth_session`，换到令牌或过期后删除；长期凭据仍按 ADR-030～032 加密保存。
- Claude Code 保持原有授权码回填流程。已经存在的旧 Codex 授权码会话仍可在过期前完成，新的 Codex
  登录全部使用设备码。

## 后果

- Codex 登录不再访问 `localhost:1455`，浏览器和 WAO 服务端位于不同机器也能完成。
- 用户仍需在 OpenAI 官方页面登录并确认一次性短码，这是官方设备码协议的安全确认步骤；确认后 WAO
  自动创建账号，无需复制回调地址或令牌。
- 设备码端点若被 OpenAI 临时禁用，WAO 会明确提示登录不可用，不再回退到必然失败的本地回环流程。
