# Phase 1：飞书登录

## 目标

为 WAO 网页应用接入飞书作为身份提供方，并覆盖：登录页扫码、登录页授权、工作台端内免登、工作台 PC 端唤起浏览器免登。

## 验收场景

1. 未登录用户可在 `/login` 选择“飞书扫码登录”或“飞书授权登录”，飞书回调后进入首页并显示已登录用户。
2. 工作台在线网页主页指向 `/api/auth/feishu/workbench` 时，服务端发起同一授权码流程；飞书客户端内完成授权后回到 WAO。
3. 工作台桌面端配置“跳转至浏览器打开新页面”并指向 `/api/auth/feishu/browser` 时，浏览器自动发起同一授权码流程。该场景仅承诺 PC 端。
4. 回调必须校验带有效期且防篡改的 `state`，失败时不创建 WAO 会话，也不得在日志、响应或前端存储飞书 `app_secret`、用户 access token。
5. 未配置或未启用飞书时，登录入口明确提示管理员尚未配置；已有 Hello 链路不受影响。

## 接口

- `GET /api/auth/feishu/authorize?scene=qr|authorization|workbench|browser`：返回对应授权 URL。
- `GET /api/auth/feishu/callback?code=&state=`：验证后重定向 SPA 回调页。
- `POST /api/auth/feishu/exchange`：以授权码交换飞书用户信息，并返回 WAO 会话资料。

## 配置与发布

应用为企业自建应用，配置网页应用、重定向 URL 和用户身份相关权限。`FEISHU_APP_SECRET` 只保存于节点私有 `deploy/.env` 或 GitHub Secret；默认 `FEISHU_LOGIN_ENABLED=false`。将 `https://wao.wayuwa.cn/api/auth/feishu/callback` 加入飞书安全设置的重定向 URL 白名单；工作台主页分别配置 `/api/auth/feishu/workbench` 与（PC 浏览器模式）`/api/auth/feishu/browser`。

飞书回调使用 OAuth 授权码模式；二维码入口通过飞书登录页进入同一回调链路。WAO 只把飞书稳定用户标识作为外部身份，后续用户/权限模型必须以该标识建立本地映射，不能把飞书 access token 作为 WAO 登录态。
