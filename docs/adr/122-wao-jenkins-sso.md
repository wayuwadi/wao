# ADR-122：Jenkins 使用 WAO 会话单点登录并按角色授权

## 状态

已采纳。本文替代 ADR-099 中“WAO 会话后仍需 Jenkins 管理员密码”的 UI 认证决定；其余 Jenkins 网络、
Webhook、构建和发布边界保持不变。

## 背景

Jenkins UI 已位于同域 `/jenkins/`，云 Nginx 也会先校验 WAO 会话，但 Jenkins 仍要求第二次输入只保存在
Linux 主节点的管理员密码。这既打断从 WAO 查看部署记录的链路，也会诱导成员索要或传播生产凭据。

直接让所有通过 WAO 会话的请求成为 Jenkins 管理员虽然改动更少，却会把普通成员提升为可修改生产流水线和
凭据的管理员。把 Jenkins 密码返回给前端则无法按成员审计，并扩大共享凭据泄露范围。

## 决策

- WAO 新增只供 Nginx 鉴权子请求使用的 `/api/auth/jenkins`：校验服务端会话后，以响应头返回成员 id 与由
  服务端角色派生的固定组，不接受客户端提交身份。
- 云 Nginx 捕获鉴权子请求响应头，清空浏览器传入的认证和身份头，再把受信任的用户名、组传给 Jenkins。
- Jenkins 安装 Reverse Proxy Auth 和 Matrix Authorization 插件。`wao-members` 仅有查看任务、视图和构建的
  权限，`wao-admins` 有管理权限；WAO 的“管理员”和“超级管理员”映射到后者。
- Jenkins 仍只监听 `127.0.0.1`，反向代理是身份头的信任边界。公开 GitHub Webhook 不经过 UI SSO，继续依赖
  精确路径、POST 限制和 HMAC 签名。
- 控制器维护脚本在 loopback 上使用固定管理身份头请求 crumb 和 `safeRestart`。首次从本地认证域迁移时允许读取
  既有管理员密码回退一次，迁移完成后日常操作不再依赖该密码。

## 后果

成员从 WAO 打开 Jenkins 不再看到第二个登录页，构建访问可归因到稳定成员 id；普通成员能查看部署证据但不能
触发或修改生产流水线。WAO 会话成为 UI 登录的唯一外部认证源，因此会话服务不可用时 Jenkins UI 会失败关闭，
但流水线本机执行和签名 Webhook 不受影响。应急访问需先恢复 WAO 会话链路或在 Linux 控制台按 Jenkins 官方
恢复流程处理，不能通过公开共享管理员密码绕过。
