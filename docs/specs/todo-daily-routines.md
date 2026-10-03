# 待办日常规则：第二阶段

## 用户结果与范围

在现有浏览器 `/todo` 的早晨、晚上区域显示固定事项。它们是独立的日常规则及状态，不复制成普通任务，不产生逾期积压，也不改变任务截止日期、小记正文或第一阶段历史。AI 推荐属于后续阶段。

## 生效时间与历史

上海业务日为日期边界，星期使用 ISO 1（周一）至 7（周日）。新建规则从**今天**生效；编辑已有规则的标题、时段、星期、排序、启用/暂停或归档状态，从**明天**生效。当天多次编辑明日版本以规则 `version` 串行化，今天的展示始终取今天版本，即使用户尚未打开日视图也不会改变。暂停和归档保留旧规则、旧版本及过去记录，恢复也从明天生效。归档规则不再生成新虚拟记录，但历史日仍能按当时版本回看。

只在版本为启用、未归档且日期命中星期时，确定性生成 `OPEN` 虚拟日实例。读取日视图不落库。用户今天标记 `DONE`、`SKIPPED` 或重置 `OPEN` 才持久化实例；实例保存当时规则版本、标题、时段、顺序和状态，后来改规则不重写既有实例。过去只能回看，未来只可预览，均不得标记。过去未曾操作的日期可以按当时有效规则显示虚拟 `OPEN`，但不算做完成，也不积压到今天。没有普通任务或 task-day 事件副本。

## API 契约

均使用浏览器会话、`ApiResponse<T>` 封装，成员和账号从会话推导；路径中的规则 ID 与操作 ID 均为规范小写 UUID。范围查询闭区间最多 31 天。

- `GET /api/todo/routines?from=YYYY-MM-DD&to=YYYY-MM-DD` 返回 `{from,to,templates,days}`。`templates[]` 为 `{id,version,currentRevision,nextRevision}`；版本为 `{id,effectiveFrom,effectiveTo,title,period,weekdays,sortOrder,active,archived}`。`days[]` 为 `{date,occurrences}`；实例为 `{templateId,revisionId,date,title,period,sortOrder,status,version,recordedAt,virtual}`。`period` 只允许 `MORNING`、`EVENING`，`status` 为 `OPEN`、`DONE`、`SKIPPED`。虚拟实例 `version=0`、`recordedAt=null`、`virtual=true`。持久化实例版本从 1 开始，`recordedAt` 带上海时区偏移。
- `GET /api/todo/routines/{id}` 返回当前成员的同一个 `template` 对象，用于刷新冲突草稿。
- `PUT /api/todo/routines/{id}/operations/{operationId}` 请求 `{expectedVersion,title,period,weekdays,sortOrder,active,archived}`。新建规则 `expectedVersion=null`，已有规则传当前正整数版本；标题非空且最多 120 字，星期不重复且为 1–7，排序值 0–10000。返回 `{operationId,template,effectiveFrom}`。
- `PUT /api/todo/routines/{id}/occurrences/{date}/operations/{operationId}` 请求 `{expectedVersion,status}`。虚拟实例传 0，已有实例传当前版本；`OPEN` 用于重置。返回 `{operationId,occurrence}`。仅首次操作必须是今天；同一 UUID 的原请求在日期过去后仍可安全重放查询。
- `GET /api/todo/operations/{operationId}` 在统一操作回执接线后对规则及状态操作返回 `{status:'COMMITTED',operationId,type,result}`；未知键或当前无成员权限为 `UNCONFIRMED`。类型为 `ROUTINE_RULE`、`ROUTINE_OCCURRENCE`。同键异请求冲突，同键同请求不重复写入。回执只保存规则 ID、日期和生效日等引用，读取时重新鉴权并取当前资料。

## 事务、权限与数据

V179 新增 `wao_todo_routine_template`、`wao_todo_routine_revision`、`wao_todo_routine_occurrence`，复用 V178 的 `wao_todo_operation_receipt`。账号行锁、规则版本和实例版本共同约束并发；规则/状态与回执同事务提交。读取和重放均按会话成员过滤，不能凭猜测 ID 跨成员读取。日期范围查询批量读取规则、版本和实例，最多返回 500 个规则、5000 个版本及 5000 个实例，超出明确报错。

日常规则只开放新 Web API，不自动扩展旧 MCP 令牌。CLI 与 MCP 的适配需单独确认权限和用户价值，本阶段没有承诺新增命令。
