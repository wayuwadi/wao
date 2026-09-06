# 未完成重点接口

需要登录，成员来自服务端会话；响应沿用 `ApiResponse<T>`。日期由服务端 Asia/Shanghai 自然日决定。

- `GET /api/workday-rhythm/focus/pending`：返回 `{ date, items: [{ date, index, title }], skippedCount }`。仅按精确键读取当前成员昨日存档，返回前三个目标中未完成且非空的项，按原位置正序排列。不扫描更早历史或个人任务；昨日无存档返回空数组。缺少旧版完成数组视为未完成，昨日存档无法解析时 `skippedCount` 为 1。
- `PUT /api/workday-rhythm/focus/pending/{date}/{index}/completion`：请求 `{ expectedTitle: string, completed: boolean }`。日期必须是上海自然日的昨天，index 为 0–2；跨日后操作旧页面应刷新。只更新原成员日存档的对应完成状态；保留其他字段。成功 `data: null`，重试已达目标状态幂等成功。

无登录返回 401；无效日期、下标或损坏存档返回 400；原文本已变化或存档发生并发修改返回 409，客户端提示刷新后重试。并发更新使用原值逐字比较后写入，避免覆盖同一日其他重点及补给记录的更新。不会改写未来计划、今日草稿或自动发送飞书消息。
