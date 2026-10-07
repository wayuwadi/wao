# 飞书每日归档 API

2026-10-07。仅管理员/超级管理员浏览器会话，使用 `ApiResponse<T>`；普通成员、匿名、设备令牌不可操作。

- GET `/api/feishu/daily-archive`：读取配置与最后一次运行。
- PUT `/api/feishu/daily-archive`：`{enabled:boolean,expectedEnabled:boolean}`，必填；服务端行锁下比较当前开关，冲突返回 409，输入保留供核对。不修改共享应用、群或知识库。
- 不提供手动运行、重试、日期选择、补录 API；旧 `/api/feishu/chat-sync/run` 保持停用。

响应：`{enabled,timezone,schedule,nextRunAt,configured,appId,chatId,chatName,kbId,latestRun}`。时区 `Asia/Shanghai`，计划 `0 0 0 * * *`，未启用时 `nextRunAt=null`。

`latestRun=null` 表示没有执行；否则为 `{archiveDate,status,runAt,durationMs,errorCode,docId,messageCount}`，status 为 RUNNING/SUCCESS/FAILED。错误只返回安全代码，不返回群内容或上游凭据。当前日期执行前持久登记，失败当天不自动重试。归档日期是执行日的前一天。

机器人活动的 `receiver.receptionMode=EVENT`，兼容字段 `pollIntervalMinutes=0`，无轮询注册。`callbackVerified` 仍仅表示配置认证，真实事件保存需检查接收/保存记录，不能用历史轮询记录替代。
