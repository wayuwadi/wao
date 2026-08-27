# Phase 1：Jenkins 继承 WAO 登录身份

对应子任务：`s17877083111112`（从 WAO 打开 Jenkins 时不再二次输入 Jenkins 密码）。

## 目标

登录 WAO 的成员从左下角链接打开同域 `/jenkins/` 后，直接以当前 WAO 成员身份进入 Jenkins；不向成员分发、
展示或复制 Jenkins 管理员密码。退出或失去 WAO 会话后，后续 Jenkins 请求立即重新被会话门禁拒绝。

## 身份与权限

- 云 Nginx 只通过内部 `auth_request` 调用 `/api/auth/jenkins`，由服务端会话返回稳定成员 id 和固定角色组；
  浏览器自带的身份、组和 `Authorization` 请求头必须在转发前覆盖或清空。
- Jenkins 使用反向代理认证域。普通 WAO 成员属于 `wao-members`，只能查看控制器、视图、任务、构建记录与日志；
  “管理员”和“超级管理员”额外属于 `wao-admins`，保留 Jenkins 管理权限。
- 匿名公网请求继续返回 401；GitHub Webhook 仍只走签名校验的精确 `/github-webhook/` 路径，不获得 UI 身份。
- Jenkins 继续只监听 Linux loopback，公网只能经云 Nginx 和反向 SSH 隧道进入；本机控制器维护脚本使用固定的
  管理身份头，旧管理员密码只作为首次切换认证域时的迁移回退，不进入浏览器或日志。

## 状态与验收

- Jenkins 由官方简体中文本地化与 Locale 插件提供资源，JCasC 固定 `zh_CN` 并忽略浏览器语言；成员从 WAO
  打开控制器时常用导航、任务页和构建页显示中文，未翻译的插件专有名词与历史日志允许保留原文（见 ADR-139）。
- WAO 会话有效时，访问 `/jenkins/job/wao-main-cd/` 不出现 Jenkins 登录页，页面身份等于当前成员 id。
- 普通成员能读取最近构建和控制台日志，但不能构建、重放、取消或配置任务；管理员保留这些操作。
- 清除 WAO 会话后访问 `/jenkins/` 返回 401；伪造 `X-Forwarded-User`、`X-Forwarded-Groups` 或
  `Authorization` 不能越过 Nginx 内部鉴权结果。
- 控制器配置更新后仍能安全重启，生产验证和飞书发布通知不因认证域切换中断。
