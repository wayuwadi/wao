# ADR-076：原生 Windows 客户端使用浏览器与本机回调完成飞书登录

## 状态

已采纳。

## 背景

任务 `t17869405414760` 要求原生 Windows 客户端支持飞书扫码登录。客户端此前只调用邮箱密码接口；已有飞书
网页授权最终把会话 Cookie 写入系统浏览器，WPF 的独立 `HttpClient` 无法获得该会话。ADR-075 又明确禁止把
网页或 WebView 嵌进原生客户端，因此不能直接复用网页二维码 iframe。

## 决策

- WPF 启动只监听 `127.0.0.1` 随机高位端口的一次性 TCP HTTP 回调，并请求
  `GET /api/auth/feishu/authorize?scene=windows&callbackUrl=...`。服务端只接受固定
  `/wao-auth/callback` 路径、无查询或片段的 IPv4 loopback HTTP 地址。
- 服务端把经过校验的 loopback 地址放入五分钟有效、HMAC 防篡改的 OAuth `state`。飞书仍回调生产 HTTPS
  地址，由服务端校验 `state` 后转回签名内的本机地址；任意公网地址、其他路径或把回调附加到非 Windows
  场景都会被拒绝，避免形成开放重定向。
- 客户端用系统默认浏览器打开飞书官方授权页。用户可在该页扫码或使用已有飞书登录态；浏览器把授权码送回本机
  后，WPF 自己调用 `/api/auth/feishu/exchange`，因此 HttpOnly WAO 会话直接落在客户端 Cookie 容器，再由
  DPAPI 按 ADR-075 保存。
- 首次飞书身份需要认领成员时，客户端展示服务端返回的真实候选成员，并在同一临时会话中调用
  `/api/auth/claim`；不自动创建成员或猜测绑定关系。等待扫码最长五分钟，用户可取消并重试。
- 不注册自定义 URI scheme，不要求管理员配置 URL ACL，也不新增客户端 Secret。授权码、飞书 access token
  和 WAO Cookie 均不写入日志或界面。

## 影响

- 扫码界面位于飞书官方系统浏览器页面，不是客户端内嵌二维码；这保留了原生客户端无 WebView 的边界，并复用
  飞书真实授权能力。
- 本机防火墙或安全软件若阻止 loopback 回调，客户端会保留登录页并显示可重试错误，邮箱入口仍可使用。
- 服务端和 Windows 客户端必须同时发布；旧客户端不会请求 `windows` 场景，不受新增参数影响。
