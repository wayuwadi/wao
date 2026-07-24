# Phase 0：Hello World 纵向切片

## 验收场景

1. 用户访问前端首页，看到 WAO 系统状态卡片。
2. 用户点击“调用后端”。
3. 浏览器请求 `GET /api/hello`。
4. Gateway 将请求转发到 Nacos 中注册的 `wao-server`。
5. 页面展示 `message`、服务名和服务端时间。

## API 行为

- 成功 HTTP 状态为 `200`。
- 响应满足统一信封：`code=0`、`message=success`、`data` 非空、`timestamp` 为毫秒时间戳。
- 未处理异常由全局异常处理器转换成 `code=50000` 的 JSON。

## 非目标

- Phase 0 不设计用户、订单等业务表。
- Phase 0 不实现 JWT；仅预留配置入口。
- Phase 0 不在仓库保存真实服务器凭据。
