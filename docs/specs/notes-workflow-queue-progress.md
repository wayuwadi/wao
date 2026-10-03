# 小记工作流批次不能被等待者堵住

补充 `notes-workflow-stage-failure.md` 与本机 Codex 执行约定。

## 问题与行为

工作流的 RUNNING 表示已提交阶段任务，可能还在模型队列中，也可能模型已经完成但工作流尚未取回结果；它不等于 Codex 进程正在运行。成员入队上限沿用此状态计数。

原查询每轮只取 updated_at 最早的 30 篇。当至少 30 篇旧 PENDING 排在较新的 RUNNING 前面，而成员名额已经用满时，每轮都读取同一批等待者，已完成模型任务无法被观察并释放名额。

- 批次先读取至少一个阶段为 RUNNING 的工作流，再读取只有 PENDING 的工作流。
- 同类按 updated_at、doc_id 排序。已读取工作流沿用既有推进和更新时间，最终状态不再进入待处理批次。
- 保持每轮最多 30 篇、成员原入队上限、任务 CAS 和本机单 worker 执行方式；不提高并发或重开旧 Runner。
- 该修改不保证模型完成时限，也不把任务失效说成已经终止模型进程。

无 API、字段、migration、CLI 或 MCP 能力变化。验收见 [队列推进验证](../validation/notes-workflow-queue-progress-2026-10-03.md)。
