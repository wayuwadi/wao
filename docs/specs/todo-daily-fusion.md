# 待办日视图融合：第一阶段

## 用户结果与范围

在现有 `/todo` 内按上海业务日期安排任务、记录与任务关联或独立的小记、回看真实发生的进展。既有全部待办、小记正文、日历和截止日期继续使用原数据。固定事项和 AI 推荐属于后续独立阶段，本规格不开放其 API 或模拟数据。

快捷新建使用稳定 ID、当前成员负责人，并明确选择可见项目；现有后端不接受空项目，不改变任务归属模型。日历选日可进入该日记录。排时标签点击后直接选择粗时段，精确时间才展开更多输入；Esc/外部点击收起并保留未提交草稿。

## 排时和兼容

`TaskDetail.schedulePeriod` 与 `TaskDto.schedulePeriod` 可为空，非空仅允许 `MORNING`、`AFTERNOON`、`EVENING`。粗粒度时段要求 `start` 有效且 `startAt/endAt` 均为空；精确时段要求本地 ISO 的开始、结束均有效，结束晚于开始，`start` 等于开始日期。无时段的单日安排仅填 `start`。`due` 始终是独立截止字段。

旧全文保存省略新字段时，只要仍有日期且未改为精确时段，就保留已经存储的 `schedulePeriod`。旧保存明确清除日期或改为精确时段时清掉粗时段。专用排时操作要求完整提交四个排时字段；`null` 表示显式清除。所有排时写入仍走任务项目权限和 `editVersion` 前置条件。

## 日事实与覆盖边界

V175 在实际执行时记录 `wao_todo_recording_scope.started_at`，并将当时每个任务的负责人及参与人成员当前快照存为 `BASELINE` 事件。它不是历史工作证据。接入前的任务进展不靠当前 `completedAt`、活动文案或现有状态倒推；已有小记只有可信 `created_at` 时按真实创建日展示。

任务创建、标题、状态、步骤、负责人、参与人、排时、父子关系、删除和日小记进展在业务写入同一事务中追加结构化事件。事件存发生时刻、业务日、类型、变更前后日视图快照；`wao_todo_task_day_entry` 只物化实际发生过变更的日期。查询某日计划时取该日结束前最后一个事件版本，按该版本执行日期判断，不要求用户曾打开页面；查询实际变化时合并该业务日事件。迟到补记保留真实写入时刻，并标为 `late`。后续改期、改名、重开不会篡改此前日末版本或事件。快照不复制小记正文。

历史查询每次先批量取当前可见任务，不能读已删除或当前无项目权限的快照。个人计划只收当时负责或参与的任务；加入成员不获加入前快照，移出成员后不再获得后续快照；发生变化当天的本人旧事实仍有记录。主任务收尾要求主状态为已完成且全部内嵌子项已完成；独立父子任务不级联。摘要的 `finishedCount` 仅统计该业务日真实从未收尾转为收尾的任务，同一任务当天多次完成只计一次；`stepDoneCount` 按同一任务内的稳定步骤 ID 去重。基线中已完成的任务不算当日成就。

## 小记

`PUT /api/todo/note-operations/{operationId}` 用稳定 `docId` 在现有小记知识库创建，或凭 `expectedDocRev` 更新同一 Doc。请求带 `businessDate`、可选 `taskId` 和 `expectedTaskEditVersion`。正文沿用 `blocks`/`content`、标签和原有显式 AI 偏好字段；本功能不复制正文。若关联任务，在同一事务中把 doc ID 加入任务 `detail_json.docs[]`，它继续是任务文档关系的权威来源。`wao_todo_day_note_context` 固定小记业务日与补记标记，后续正文编辑不因更新时间换日。未有 context 的普通小记按真实 `doc.created_at` 展示。已删除、归档或作者不符的小记不在日页或回执中泄露。

## 操作和查询契约

- `GET /api/todo/days/{date}` 返回 `date`、`recordingStartedAt`、`historyCoverage`、`tasks[]`、`notes[]`、`summary`。任务项同时含当前可见 `task` 和日末 `snapshot`；历史展示标题、状态及步骤以 `snapshot` 为准。`events[]` 给出 `type`、带 `+08:00` 的 `occurredAt`、`businessDate`、`late` 和固定类型说明。小记只返回目录引用，不返回全文。
- `GET /api/todo/days?from=&to=` 返回闭区间最多 31 天的摘要，以一次范围版本窗口和范围事件、小记查询合并，不逐日访问数据库；超范围报错。单日查询任务快照上限 2000、事件 500、小记 200，越限明确报错，不静默截断。
- `PUT /api/tasks/{id}/schedule-operations/{operationId}` 请求 `{expectedEditVersion,start,startAt,endAt,schedulePeriod}`，五个键必须存在；返回 `{operationId,task,previousSchedule,appliedAt}`。撤销必须用新 operationId 和成功结果的后置 `editVersion`，仅在任务仍未被他处修改时补偿。
- `PUT /api/todo/note-operations/{operationId}` 请求 `{docId,expectedDocRev?,expectedTaskEditVersion?,businessDate,taskId?,title,blocks?,content?,tags?,autoTitle?,autoImage?,autoPolish?}`；返回 `{operationId,doc,task,businessDate,late}`。
- `GET /api/todo/operations/{operationId}` 返回 `{status,operationId,type?,result?}`；未知键为 `UNCONFIRMED`，已提交且当前仍有权限为 `COMMITTED`。

操作 ID 为规范 UUID；回执按真实会话 `account_id` 隔离，只保存请求哈希、目标 ID、版本及业务日期等轻量引用，不存第二份小记或任务正文。同键同请求确认原操作已提交，并从权威 Doc/Task 取当前版本；同键异请求冲突。重放时重新鉴权，当前资源删除、归档或撤权时不返回旧正文。账号行锁串行化同一账号的操作，业务写入和回执同事务。小记自动整理及已有通知在事务提交后调度，失败不误报业务写入失败。

## 权限与暂缓开放

本期仅浏览器登录会话可调用新的日聚合和操作；不因新增 HTTP API 自动给旧 MCP 令牌扩权。现有 MCP/CLI 经公共任务服务写入仍会留下必要日事件。若后续有脚本化日排程或进展回顾需求，再分别定义最小读取、写入权限与反馈语义。
