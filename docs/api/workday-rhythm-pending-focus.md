# 未完成重点接口

需要登录，成员来自服务端会话；响应沿用 `ApiResponse<T>`。日期由服务端 Asia/Shanghai 自然日决定。

- `GET /api/workday-rhythm/focus/pending`：返回 `{ date, items: [{ date, index, title }], skippedCount }`。读取当前成员今日之前全部日存档，按原日期倒序、原位置正序返回未完成且非空的重点。缺少旧版完成数组视为未完成；无法解析的存档计入 `skippedCount`。
- `PUT /api/workday-rhythm/focus/pending/{date}/{index}/completion`：请求 `{ expectedTitle: string, completed: boolean }`。日期必须早于今日，index 为 0–19。只更新原成员日存档的对应完成状态；保留其他字段。成功 `data: null`，重试已达目标状态幂等成功。

无登录返回 401；无效日期、下标或损坏存档返回 400；原文本已变化或存档发生并发修改返回 409，客户端提示刷新后重试。并发更新使用原值逐字比较后写入，避免覆盖同一日其他重点及补给记录的更新。不会改写未来计划、今日草稿或自动发送飞书消息。
