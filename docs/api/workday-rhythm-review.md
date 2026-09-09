# 每日复盘接口

- `GET /api/workday-rhythm/days/{date}/review`：读取当前登录成员复盘，返回 `ApiResponse<{revision: number, entries: string[]}>`。不存在返回版本 0、空数组，不创建记录。
- `PUT /api/workday-rhythm/days/{date}/review`：提交同结构，revision 为读取版本。最多 20 条，每条 1000 字，保留原文和空条目。成功返回新版本；同内容重试返回现版本；过期版本或并发更新返回 409。未登录返回 401。日期采用 ISO 自然日，支持 2000—2100 年。
- `GET /api/workday-rhythm/history`：每日记录增加 `focusCompleted: boolean[]`（与过滤后的 focus 一一对应）和 `review: string[]`（原始编号与换行）。仅有复盘且没有工作日依据时 workdayActive 返回 null，不猜测为休息日。同样按本人、有界月份范围查询；复盘与原日存档按日期合并，损坏记录累加 skippedCount。

存储：独立成员偏好键 `workday-rhythm-review-YYYY-MM-DD`。原始日存档不改结构，计划、补给、昨日未完成写入不会覆盖复盘。
