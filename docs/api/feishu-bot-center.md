# 飞书机器人管理 API

2026-10-07。仅允许管理员／超级管理员浏览器会话；拒绝普通成员、匿名和设备令牌。除平台回调外使用 `ApiResponse<T>`。

## 注册表

沿用 GET/POST `/api/settings/webhooks`、PUT/DELETE `/api/settings/webhooks/{id}` 与 POST `/api/settings/webhooks/{id}/test`。列表与写响应仍只返回 `urlMask`、`urlSet`，不返回秘密。

配置增加 `captureKind`（NONE/TASK/NOTE）、`mentionId`、`mentionName`、`appId`、`chatId`、`revision`。可选稳定提及标识优先；名称仅为明确配置的精确兼容。关联使用已配置的共享接收应用／群。

编辑携带 `revision` 检查并发版本；影响现有引用的变更携带 `impactConfirmed=true`。DELETE 使用 query 参数 `revision`、`impactConfirmed`。版本冲突不能静默覆盖；影响未经确认不执行。默认接管契约沿用。

## 活动与影响

GET `/api/settings/webhooks/{id}/activity` 返回：

- `usages[{kind,label,count}]`：实际默认路由、群消息收集与保留的业务选择。
- `recentSends[{kind,status,occurredAt,errorCode}]`：TEST/SEND 和 SUCCESS/FAILED。
- `recentCaptures[{messageId,captureKind,status,targetId,occurredAt,errorCode}]`：最近真实来源事件／持久接收处理结果。
- `receiver{enabled,appConfigured,chatConfigured,callbackVerified,appId,chatId,chatName,eventUrl,pollIntervalMinutes}`：实际共享配置。`callbackVerified` 只表示已设置事件认证，不能当作实际收集成功。
- `canDelete`：true 表示无引用可直接删除，false 表示需要显式影响确认；`replacementId/replacementName` 提供默认项删除后的预期接管目标。

记录最多二十项，错误只返回安全代码，无正文、凭据或上游异常明文。发送统计持久写入失败不将已经送出的消息伪装成发送失败。

## 接收

共用 `/api/feishu/chat-sync/config` 与 `/api/feishu/quick-collect/config` 管理认证和群。`/api/feishu/events` 仍遵守飞书 challenge／事件协议，创建前认证，合法消息加密持久接收后快速应答、后台处理。不能用管理 API 或设备令牌伪造群消息。
