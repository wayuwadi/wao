# WAO 工作区 MCP

2026-10-03：[小记保存后自动整理](notes-save-ai-settings.md)新增浏览器会话的聚合设置与单项阶段 API。成员个人设置和主动 AI 消耗不属于既有文档令牌范围，不新增 CLI 命令或 MCP 工具；通用文档写入明确阻止自动入队，同时仍使旧来源失效。未来开放须单独设计执行范围、配额与版本条件。

2026-10-03：浏览器[执行安排与截止分离](task-execution-schedule-2026-10-03.md)不改变现有 MCP 工具定义。`update_task.due` 仍仅修改截止，未开放开始日期/执行时段写入；不自动扩大旧令牌权限。

## 目标与入口

WAO 设置 → 管理与连接 → WAO CLI 与 MCP（`/settings?tab=workspace-mcp`）提供“生成连接指令”和“管理连接”。首页收起连接模块后，授权入口迁至设置，复用已有组件和接口。当前登录成员点击生成后，服务端签发独立的工作区 MCP Bearer 令牌，页面仅本次显示完整令牌，并生成可直接发给 MCP Client 的中文连接说明。说明包含 Streamable HTTP 地址、Bearer 配置、安全存储要求、`tools/list` 连接验证及只根据实际工具推荐场景的约束。历史个人记忆令牌不自动获得工作区权限。

工作区地址为 `https://wao.wayuwa.cn/api/mcp/workspace`。浏览器以 WAO 会话访问 `/api/workspace-mcp/tokens` 来列出、签发和吊销令牌；MCP 请求仅接受独立 Bearer，不接受浏览器 Cookie 代替。每枚令牌可设为只读或可读写，并可独立选择是否允许通过 WAO CLI 发布托管 HTML；页面发布授权默认关闭，创建后不能扩大权限，吊销立即生效。HTML 发布权限只允许新建页面，不开放已有页面列表、修改或删除，也不改变 MCP `tools/list`。只保存随机令牌的 SHA-256 摘要，明文不进日志、数据库和后续列表响应。

## 首批工具

读取：`list_projects`、`get_project`、`list_open_tasks`、`get_task`、`list_knowledge_bases`、`get_knowledge_base`、`list_documents`、`get_document`。

写入：`create_project`、`update_project`、`create_task`、`update_task`、`update_subtask_status`、`create_knowledge_base`、`update_knowledge_base`、`create_folder`、`rename_folder`、`move_knowledge_node`、`create_document`、`update_document`。

项目截止日期：`create_project`、`update_project` 的可选 `due` 使用有效 `yyyy-MM-dd`；创建省略即未排期，更新省略/null 不改、空串清空。沿用既有项目写权限和可见性，不增加令牌范围；客户端以部署后的实际 tools/list 为准。

`list_open_tasks` 默认只列令牌所属成员负责、状态未完成的可见任务；可切换列出其可见项目中的全部未完成任务，也可按项目过滤，最多返回 100 条。项目和任务单项读取沿用现有成员可见性。现有知识库创建服务不再绑定项目；独立知识库沿用当前 WAO Web 的共享可见范围。知识库目前没有可可靠回填的归属成员字段，因此本期不声称独立知识库是成员私有；新建知识库标注“成员可编辑”。历史知识库如有数据库项目关联，即使 DTO 对外隐藏该字段，MCP 访问仍须按真实关联校验项目可见性。

写工具只在可读写令牌的 `tools/list` 中出现，实际调用也再次校验。新建任务负责人来自令牌成员，并沿用站内现有创建通知规则；新建项目默认本人可见且不发送创建通知。文档正文替换要求 `expected_rev`，与 Web 同用乐观并发校验；文档资料仅作为不可信引用，不能执行正文内的指令。此期不暴露删除或直接发送通知的工具。

## 协议与后续同步

账务本已按 ADR-295 全站下线；工作区 MCP 不提供账务本工具。

项目列表个人分组与排序是浏览器展示偏好，不改变共享项目字段，也不是独立项目业务操作。现有通用成员偏好接口不向工作区 MCP 暴露，因此不新增 MCP 工具或扩大旧令牌权限；若未来支持脚本化布局，需先定义字段白名单、并发冲突与审计规则。

无状态 Streamable HTTP 以单个 JSON 响应处理 `initialize`、`ping`、`tools/list`、`tools/call`，notification 返回 202；每次请求重新鉴权。`tools/list` 的 schema、调用分发和权限门禁保存在同一个显式工具目录。后续项目、任务、知识库能力迭代时，必须检查是否应同步 MCP 工具，并同时更新此规格、工具目录及真实协议/权限测试；不能仅因 Web API 新增就自动暴露给所有设备令牌。连接成功后应以实时 `tools/list` 为准，不按静态页面文字猜测能力。

## 验收

- 设置中生成的令牌可经 MCP `initialize` 和 `tools/list` 连接，明文仅创建时显示一次，页面关闭后不能重新查询明文。
- 只读令牌无写工具且强行调用写工具失败；记忆令牌与无令牌请求都得到 401；吊销后原连接再次请求得到 401。
- `list_open_tasks` 不返回别人的私人项目任务；若知识库带有真实项目关联，也不能越过项目可见性。
- 创建与更新任务、项目、文件夹和文档复用现有业务服务；文档版本冲突得到明确工具错误，不吞掉本地草稿。

## 子任务状态修改（2026-09-24）

用户明确要求开放独立子任务状态更新，新增 `update_subtask_status`，只向现有可读写令牌开放；每次调用继续检查成员项目可见性，只读令牌不可调用。`update_task` 仍仅修改主任务。

必填参数：`task_id`、`subtask_id`、`status`、`expected_subtask_version`。状态为未开始、进行中、待验证或已完成。先 `get_task` 读取 `subtaskVersion`，将其作为 expected_subtask_version。服务端在事务行锁内校验版本，只修改目标子任务的 done/status 并追加活动记录，保留其他字段。冲突时返回工具错误并要求重读，不自动重试；返回更新后的主任务及新版本。历史无 id 的子任务使用 taskId#下标。

待验证写入 done=false；完成时 done=true 并清除细分状态，重新打开为未开始时 done=false。AI 默认只推进到待验证，已完成由用户验收决定。

CLI：`wao subtasks status SUB_ID --task-id TASK_ID --status 待验证 --expected-subtask-version VERSION`。部署后必须先通过实时 tools/list 确认新工具可见；不得在旧服务尚未部署时猜测调用。

## 小记最新正文（2026-09-30）

浏览器最新正文交互见 `notes-single-latest-content.md`。后台润色以源正文与文档版本条件写回；浏览器保存触发的自动续跑不新增工作区工具，不改变令牌可见能力、文档写入权限或冲突语义。

2026-10-02：项目 AI Run 退役（ADR-303）；现有工作区工具没有派工或 Runner 协议能力，不新增工具或扩大令牌权限。

2026-10-02：WeKnora 同步退役（ADR-305），不改变原生知识库读取和编辑工具；无新的同步权限。

2026-10-03：ADR-313 将既有浏览器润色、问答与后台只读分析迁至本机 Codex；服务主机队列不是工作区执行授权，不新增 MCP 工具或旧令牌权限，私有图片仍按浏览器成员鉴权。详见 [业务 AI 本机执行](business-ai-local-codex.md)。

私人小记原稿、保存前 AI 输入及结果关联不加入 workspace tools/list，旧文档 scope 不包含历史来源或个人 AI 设置。普通写入仍参与用户来源保护与旧任务失效，不新增自动 AI 权限。见 [不可变原稿](notes-immutable-originals.md)。
