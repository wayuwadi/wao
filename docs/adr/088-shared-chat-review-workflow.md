# ADR-088：聊天复盘使用共享持久化编排与非阻塞只读 Codex 队列

## 状态

已采纳。复用 [ADR-019](019-feishu-chat-sync-and-system-settings.md)、
[ADR-023](023-chat-sync-history-and-monthly-archive.md)、[ADR-041](041-codex-backed-ai-assistant.md)、
[ADR-044](044-local-codex-assistant-routing.md)、[ADR-045](045-persistent-assistant-conversations.md) 和
[ADR-072](072-grounded-periodic-life-review.md) 的飞书归档、只读 Runner、会话隔离与证据校验边界。

## 背景

WAO 已能把当前唯一配置的飞书群聊按上海自然日覆盖归档到知识库，也已有任意在线本地 Codex
Runner 可领取的只读 `ai_assistant_run` 队列。娃哥和娃弟需要的不是一次临时问答，而是一条可以跨页面、跨设备、
跨服务重启恢复的固定链路：先重新同步完整日期范围，再从同批最新资料中只取两位固定发言人，最后生成并保存
可追溯的四段复盘。

现有 `AiAssistantService.runReadOnlyJob` 会在 HTTP 或调度线程里最长同步等待五分钟；用它串行处理最多 31 天的外部同步与
AI 分析，无法让页面立即获得运行 id，也无法在请求断开后继续展示真实阶段。反过来把这个需求塞进 `automation_job` 的通用步骤则会
扩大 ADR-010 已限定的自动化执行范围，把一个固定业务流程误建成通用工作流平台。

## 决策

### 共享配置与权限

- 新增内置模块 `chat-reviews`，路由为 `/chat-reviews`，归入公共分组。一期的读取、配置、发起、重试和按原参数再次运行
  均对所有已登录 WAO 成员开放；运行和报告不按创建人过滤，`created_by` 只用于追溯。
- 配置保存在现有 `system_setting` 键 `feishu.chat-review.speakers`，JSON 仅包含当前群 `chatId` 和两个不同的
  `speakerIds` (飞书 `open_id`)，不复制展示名、App Secret 或其他飞书凭据。候选人、保存校验和展示名解析均以当前
  `feishu.chat-sync.senders` 已配置成员映射为准；读取或保存复盘配置时不实时调用飞书成员 API，避免为设置页面增加长网络依赖。
- 飞书同步的 App、群、知识库和发言人映射是一个逻辑配置：保存各 `feishu.chat-sync.*` 键必须处在同一数据库事务，读取时通过一次批量 SQL
  在同一事务快照中取得全部键，不能逐键读取后拼出群、知识库和成员映射的新旧混合状态。
- 保存复盘配置和新建运行时都重新校验群、知识库完整性与发言人。若同步群或发言人映射变更，旧复盘保留快照并可继续阅读，
  但页面必须重新选定两人后才能新建；若仅知识库变更，新运行快照新知识库，旧运行仍按下述规则拒绝执行、重试或再次运行。

### 专用持久化编排

- 新增纯追加的 `chat_review_run` 表。一条记录同时是运行档案；只有 `SUCCEEDED` 记录才是可阅读的报告，但终态历史同时保留 `FAILED` 尝试。表中保存群、知识库
  (`kb_id`)、两位发言人、日期和重点输入快照，及
  `WAITING / SYNCING / VERIFYING / ANALYZING / SUCCEEDED / FAILED` 阶段、全范围日期进度、关联的 `ai_assistant_run`、来源元数据、
  四段报告、错误和时间戳，不保存聊天正文副本。
- 提交接口只在事务中校验参数并插入 `WAITING`，立即返回 `202 + runId`。后台专用 worker 通过数据库条件更新取得有期限的
  lease，不在 HTTP 线程或 Spring 其他调度器中串行等待整条流程。lease 过期后其他节点可恢复；外部调用期间不持有长数据库事务。
- `PUT /api/chat-reviews/config` 与 `POST /api/chat-reviews` 的请求体必须恰好是一份 JSON 对象；空请求体、重复键、首个 JSON 值后的尾随 token、
  非法 JSON、缺失或额外字段都在进入业务事务前返回 400。`retry` 与 `rerun` 是无参数动作，请求体必须为空，任何非空正文同样返回 400，避免客户端误以为重传参数会生效。
- worker 写入阶段、进度、来源、报告或错误时都必须在 SQL 中同时校验当前 `worker_token`、未过期 `lease_until` 和活动阶段。claim、续租和
  `ai_run_id` 关联统一以 MySQL `CURRENT_TIMESTAMP(6)` 比较并延长租约，不使用各 JVM 的墙上时钟判定所有权。受影响行数为 0 即表示租约已丢失，
  旧节点必须停止，不得覆盖新接管者。AI 运行入队与关联在同一事务内使用相同 fencing；关联失败时回滚新 AI 运行。
- 活动阶段是 `WAITING / SYNCING / VERIFYING / ANALYZING`。一期只支持一个配置群，因此生成列在任意活动阶段固定返回 `active`、终态返回 `NULL`，
  唯一索引在数据库层保证全局最多一条活动运行。切换群或知识库配置不会隐藏旧活动运行；`GET /api/chat-reviews` 不按当前 `chatId` 过滤，
  新建、重试或再次运行在旧活动运行结束前仍返回 409。先查再插和单 JVM `synchronized` 都不是可接受的并发门禁。
- worker 崩溃后可从输入快照重新同步全范围；按日覆盖保证这个恢复是幂等的。已关联 AI 运行时先查询其结果，不重复入队。
- 同步完成后保留 `N/N` 的全范围日期进度；`VERIFYING`、`ANALYZING` 和 `SUCCEEDED` 不把它改写为 AI 子阶段的 0/1 或 1/1。
- `POST /api/chat-reviews/{id}/retry` 只接受失败运行且请求体为空，复制其群、知识库、两位发言人、日期和重点快照，新建另一条 `WAITING`
  并用 `retry_of_id` 追溯失败尝试。重试从第一天重新同步；当前同步群或知识库与快照任一不同时拒绝。
- `POST /api/chat-reviews/{id}/rerun` 只接受成功运行且请求体为空，不接收调用方重传的参数，而是复制原运行的群、知识库、两位发言人、日期和重点快照，
  新建另一条 `WAITING`。当前同步群或知识库与快照任一不同时拒绝；旧报告始终保留，不被再次运行覆盖。

### 静默同步与证据边界

- 复盘范围按 `Asia/Shanghai` 自然日闭区间处理，最早为 `1970-01-01`、最多 31 天，`endDate` 不得晚于上海当天。后台按日调用现有飞书同步，
  仍然按月建目录并覆盖同日文档，但传入明确的 silent 选项：不逐日发送“群聊记录已沉淀”通知，一期也不新增复盘完成群通知。
- 飞书每日归档的稳定身份是 `(kb_id, auto_sync_scope, auto_sync_date)`，其中 `auto_sync_scope = chat_id`，V77 在 `doc` 新增这两个身份列、
  `auto_sync_version` 以及 `(kb_id, auto_sync_scope, auto_sync_date)` 唯一索引。标题只负责展示，不再作为跨群幂等键；同一知识库中切换到另一个群后同步同一天，必须创建独立文档，
  不能覆盖旧群已有报告指向的来源。
- 旧标题归档的归属只在 V77 migration 执行时确定，不在运行时猜测。migration 读取当时 `system_setting` 的
  `feishu.chat-sync.chat-id / kb-id`，在该知识库中匹配 `auto = 1` 且标题满足 `飞书群聊 · YYYY-MM-DD` 的文档。同一知识库与标题有多篇时，
  优先回填未软删除文档中的 `MIN(id)`；只有全部候选都已软删除时，才回退到全部候选的 `MIN(id)`。被选中文档写入
  `auto_sync_scope = chat_id`、标题日期和 `auto_sync_version = 0`，保留原 `docId` 和链接，也避免存活副本与恢复后的已删副本同时可见。重复标题的其余文档及未匹配旧文档保持无身份，
  后续任何群都不得认领。
- 每次飞书消息查询发起前，服务端先通过 `feishu_chat_archive_sequence` 的单行锁事务签发跨节点单调递增版本。知识库父行锁串行化同一库的身份查找和新建；
  `DocMapper` 更新时只接受精确身份和更大的 `auto_sync_version`。查找必须包含软删除行；命中后原地清除删除标记、移回目标月份目录并覆盖正文，保留同一 `docId`，
  避免之后恢复旧文档产生重复身份。旧快照晚到必须失败，不能返回较新的文档并把旧批消息绑定成该文档的复盘证据。
- 已绑定 `auto_sync_scope / auto_sync_date` 的归档仍允许在知识库手动编辑。通过文档 `PATCH` 修改标题、正文、链接或所在目录时，先从同一全局序列签发更大版本，再通过将文档 id、scope、日期和读取时 `expectedVersion` 一起入条件的专用 CAS 更新。
  并发同步或另一次 `PATCH` 先胜时返回 409 并要求刷新；旧同步快照无法覆盖这次修改，但之后新签发的同步仍可按自动归档语义覆盖它。文档树拖拽另走窄更新，只写 `folder_id / ord`，不回写正文、链接或归档版本，不签发新版本；下一次自动同步可将 scoped 归档放回固定月目录。不得因为增加归档身份而把文档改为只读。
- 飞书客户端连接和读取超时分别固定为 10 秒与 30 秒，避免外部调用无限占用 worker。复盘同步在日边界、消息渲染和图片处理期间定期续租心跳；
  每次心跳都使用数据库时钟验证并延长租约，失去所有权即停止后续归档和分析。每一日开始执行前及同步内部均重新核对当前群与运行快照的 `chat_id / kb_id`，任一变化即失败。
- 每一天的内部同步结果返回日期、知识库、文档 id、文档链接、消息数与同批结构化消息。分析必须同时满足
  `senderId == 已配置 open_id` 和 `sender_type == user` 才保留消息，不得从展示名反推身份；机器人即使因错误配置而使用了已选 id，
  也必须被硬过滤，同名成员和其他成员同样不得混入。飞书归档和 AI 证据必须来自同一次拉取，不能用后续可变的显示名重新解析身份。
  AI 材料以配置顺序给两个 `open_id` 固定分配“对象A”和“对象B”，参数保存 `label + name`，消息发言人写为“对象A（姓名）/对象B（姓名）”；即使两个展示名完全相同，也不得合并身份或丢失对话归属。
- 任意一天同步失败即停止，保存 `failed_date` 和真实错误，不入队 AI。同步进度始终以请求的全部日期为分母。全范围成功同步后，
  每个日期都按升序保存一条 `S1…Sn` 最新归档元数据，以核对完整覆盖；该日两位目标发言人没有有效消息时 `messageCount` 为 0。
- 只有 `messageCount > 0` 的来源及其消息会进入 AI 动态材料。服务端引用校验同样只把这些编号视为有效证据；即使 0 消息日期存在于完整来源清单，
  报告也不得引用。
- 文本、富文本和已能提取的交互卡片仅保留可读文字；图片、语音、视频和文件分别以现有占位或文件名表示。不对图片或视频声称视觉识别，
  不对语音声称转写。消息按日期和发送时间升序，相同时间使用飞书消息 id 作稳定次序。
- 复盘重试和多节点并发同步可能重复下载同一张飞书图片。V77 为 `image_blob` 增加 nullable SHA-256 `dedupe_key` 唯一键；只有服务端明确以
  `deduplicate = true` 转存的飞书图片才写该键，普通用户上传保持 `NULL`，继续允许同图多份。并发插入同一内容时唯一键只允许一条 LONGBLOB 获胜，失败方查询并复用赢家 id，
  避免按日重试不断堆积相同二进制正文。

### 非阻塞只读 AI 与报告验证

- `AiAssistantService` 拆出服务内部的“非阻塞入队”和“按 id 查询结果”能力；它们不是新的公开通用工作流 API。现有同步
  `runReadOnlyJob` 可作为兼容包装继续服务周报、人生回顾等旧调用方；它使用普通 `queued / running`，保留五分钟同步等待与终止语义，不伪装成可跨请求恢复的 `secure_*` 任务。聊天复盘与页面助手都在每次运行新建的临时空 Git 仓库中执行，
  使用 `read-only` sandbox 和独立的临时 `CODEX_HOME`；结束后删除仓库与该运行的 Codex 配置目录。
- 聊天复盘等内部安全只读任务以 `secure_queued` 入队，领取后转为 `secure_running`，不与成员页面助手的 `queued / running` 状态混淆。Runner 只能通过独立的
  `POST /api/ai/runner/assistant-v2/claim` 和 `POST /api/ai/runner/assistant-v2/{runId}/complete` 领取与完成这类运行；旧 `assistant` 路由在新 API 节点直接 404，新 Runner 访问旧 API 节点得到 404 时也只重试 v2，
  不回退到旧 claim / complete 协议。Nginx 只把可能涉及凭据出站的 v2 claim 固定到 Linux 主节点；无凭据的 v2 complete 保持双节点路由。
- 内部入队不以“此刻是否有哈希兼容 Runner 在线”作为前置条件。调用方负责先按业务契约组装有界提示；入队服务确认当前构建打包的安全 Runner 脚本可读、提示非空且同一操作无活动运行后，就持久化 `secure_queued`；此时不查在线节点或其哈希，只在 assistant-v2 claim 时检查 Codex 能力和脚本哈希。因此暂无兼容 Runner 时任务可在队列中等待，
  但最多只保留到 `secure_queued` 的默认 60 分钟数据库硬超时，不无期保留完整 prompt。
- 服务端把自身构建中打包的 Runner 脚本原始字节 SHA-256 作为安全能力门禁：只有心跳上报完全相同 `script_hash` 且具备 Codex 能力的节点才能领取。服务端未打包脚本、节点未上报或哈希不同时均不下发任务。
  因此滚动发布中的旧 Runner 既不会调用 v2，也无法通过哈希门禁；旧 API 节点不识别 v2。两端的不匹配都必须 fail-closed，任务留在安全状态直到兼容节点处理或数据库硬超时，不得降级为带工具执行。
- Runner 对这类只读分析固定传入 Codex `--ephemeral`、`--ignore-rules`、`--disable shell_tool`、`--disable multi_agent`、
  `tools.view_image=false` 和 `web_search=disabled`，从进程能力层关闭本机文件 / Shell、图片、联网检索和子代理扩散面；启动子进程前还必须从环境中
  剥离 `GH_TOKEN`、`GITHUB_TOKEN` 及同类 GitHub Token。聊天正文中的提示注入因而不能借这些工具读取本地资料、凭据或调用 GitHub，这一边界不依赖模型仅凭自然语言服从提示。
- 上述安全开关是强制条件。旧 Codex CLI 不识别任一开关时按非零退出失败，不得删除开关后重试或降级成带工具执行；非零退出产生的部分模型输出不得回传或持久化，
  Runner 只回传不含输出正文的稳定失败类别与退出码，服务端只保存有界稳定错误。AI 运行进入成功、失败或超时终态时都立即将 `prompt_text` 显式置空，
  不在队列表长期保留聊天证据正文。
- `assistant-v2` claim 为每次领取签发唯一 `claimToken`，并下发根据安全只读执行硬超时扣除保护余量得到的 `maxRuntimeSeconds`（默认 30 分钟硬超时时为 1,740 秒）。Runner 用本地单调时钟计算子进程截止时间，
  不用可回拨的墙上时钟；超时后停止 Codex，按失败完成且不回传部分输出。成功或失败完成必须同时匹配运行 id、Runner id、`claim_token` 和对应的执行中状态才可 CAS 落库；终态清空 token。迟到的旧 attempt 不得覆盖终态。
  无论 Codex 成功、失败、超时或 v2 完成请求在滚动发布中暂时失败，Runner 都必须在尝试回传后清理该运行的临时空仓库与 `CODEX_HOME`。
- 内部只读 `ai_assistant_run` 的生命周期时间统一由 MySQL 签发：入队以 `CURRENT_TIMESTAMP(6)` 写 `created_at / updated_at`，领取以同一数据库时钟写
  `claimed_at / updated_at`，成功、失败或超时终态以数据库时钟写 `finished_at / updated_at`。这些写入与下述超时比较都不使用 API / Runner 节点的本地时间，
  避免节点时钟漂移让任务提前过期或长期残留。
- 内部只读 AI 运行不能依赖页面或聊天复盘 worker 持续查询才发现超时。服务端后台定期清理超过
  `wao.ai.read-only.queued-timeout`（默认 60 分钟）的 `secure_queued` 运行和超过 `wao.ai.read-only.running-timeout`（默认 30 分钟）的 `secure_running` 运行，
  用 `DATE_SUB(CURRENT_TIMESTAMP(6), ...)` 与数据库时间列比较并以活动状态条件更新为 `failed`，保存有界稳定超时错误并清空 `prompt_text / claim_token`。
  安全状态不参加成员页面助手的“Runner 心跳过期后 3 分钟 JVM 时钟重排”；`secure_running` 只能由 MySQL 时钟的 30 分钟硬超时转为失败，不再回到队列被重复执行。清理与 Runner 完成并发时只允许一个终态 CAS 成功，迟到结果不得复活已超时运行。
- AI 仅接收经服务端组装的参数 JSON 与有效来源 JSON。参数 JSON 包含群名、起止日期、“对象A / 对象B”稳定标签、各自名称和可选“本次重点”；来源 JSON 只包含
  `messageCount > 0` 的日期及其编号、日期、消息时间、带稳定对象标签的发言人和正文。两个完整序列化 JSON 字符串的 Unicode code point 总数不得超过 60,000，因此计数包括
  `focus`、日期时间、姓名、字段名、引号等 JSON 序列化开销。超限或没有任何有效消息时在
  `VERIFYING` 阶段失败并要求缩小日期范围，不取前 60,000 字、不随机抽样、不进入 AI 队列。
- 来源正文用 JSON 字符串封装并明确标记为不可信引用；这是给模型的语义分隔与服务端验证前置提示，不是唯一的安全边界。即使正文要求改变结构、
  泄露凭据、执行命令或忽略上位规则，强制的无工具进程配置仍阻断本地读取与权限扩散，输出还必须通过后续固定结构和引用校验。
- AI 只能返回 `discussionProcess`、`discussionDirections`、`conclusions`、`recommendations` 四个非空字符串，每段最多 6,000 字符，
  且仍受现有 Runner 30,000 字结果上限约束；原始 JSON 因转义开销超限而被截成非法结果时同样按分析失败处理，不保存部分报告。服务端拒绝非法 JSON、缺失或额外字段、非字符串、超长字段，以及会让浏览器外联的 Markdown 图片 / 链接、URL、HTML 或引用式链接定义。
- 前三段每段至少引用一个实际存在的 `[S编号]`，全部引用都不得越界。`conclusions` 必须各有一个非空的“已明确：”、“暂定：”、“未解决：”分类；
  分类可出现在文本 / 句子边界，或作为 Markdown 列表项、标题开始，但只在正文中提到三个词不算分类。`recommendations` 必须以“AI 建议：”或同名 Markdown 标题开头且后接非空内容，
  不能在正文中事后补一句标记。只有全部验证通过才在一个事务中写入 `report_json` 并转为 `SUCCEEDED`；失败时保留来源元数据和
  错误，`report_json` 保持空。

### V77 首次发布的安全窗口

- V77 开始要求飞书归档的同步与文档 `PATCH` 编辑都经过全局版本栅栏，但旧 `wao-server` 二进制不知道这些列和 CAS。文档树拖拽是不回写正文和版本的窄更新，不在此栅栏范围。不在 MySQL 中增加兼容 trigger：生产复制链的 binlog / trigger 权限、`DEFINER` 可移植性，以及 trigger 失败后与应用回滚无法作为一个可靠事务恢复单元，都会引入更大的上线和回退风险。
- 每次发布先执行 `rollout-status`；只有 Flyway 已记录 V77 成功且持久标记 `deployment.v77.rollout-state = complete` 时才走常规滚动流程，其余情况都必须进入 V77 一次性维护窗口。判定不依赖单次 push 的文件列表，且 CI 不允许后续 push 取消这个已开始的发布。流水线先将云 Nginx 的 master / slave 两个 upstream 都标记为 down，并在修改 job 前确认 Windows 从节点可达、上传静止脚本。随后以 `INSERT IGNORE` 首次保存飞书聊天同步 automation 原状态到 `deployment.v77.feishu-sync-status`，写入 `deployment.v77.rollout-state = in-progress` 后暂停该 job，再通过
  `deploy/scripts/quiesce-wao-server.sh` 和 `quiesce-wao-server-windows.ps1` 确认停止所有旧 `wao-server`，最后才允许新节点启动 Flyway V77。任一旧节点不能确认已停止时必须 fail-closed，不得只靠公网摘流就继续 migration。
- 只有运行全新二进制且健康检查成功的节点才可重新入流。发布脚本若自动回滚出旧容器，流水线立即再次 stop；整个 V77 发布未完成时 master 保持 down，不为了恢复服务而放回旧二进制。全新节点成功后才执行 `complete-v77-rollout`；它在同一事务中先核对 Flyway V77 成功记录、`deployment.v77.feishu-sync-status` 和目标 automation job 均恰好存在，然后恢复原状态、写入 `deployment.v77.rollout-state = complete` 并删除保存键。任一前置不满足或事务失败时均 fail-closed，保持 marker 未完成；completion 返回后 CI 还要再执行一次 `rollout-status`，双条件复核为 `complete` 后才恢复 master 流量。修复故障后重试同一 completion 动作，不单独猜测并恢复 job 状态。详细故障处理见
  [Phase 0 实机上线清单](../runbooks/phase-0-rollout.md#v77-首次发布安全窗口)。

### MySQL 9 验证基线

- 数据库并发与时间语义使用 `mysql:9.0` Testcontainers 集成测试，不以 H2 或 SQL 字符串检查替代。Docker 可用时从 Flyway V1 执行到 V76，预置旧聊天归档后再执行 V77，
  覆盖完整 V1–V77 迁移链、重复旧标题优先存活 `MIN(id)` 且仅全删时回退全体 `MIN(id)`、全局活动运行唯一约束与 lease 抢占、归档身份 / 版本 CAS / 软删原地恢复、跨连接归档版本、AI 入队关联失败整体回滚，
  复盘运行时间的数据库签发与 `+08:00` 序列化，以及数据库时钟驱动的 `secure_*` 超时清理与“不走 3 分钟 JVM stale requeue”。
- 开发机或 CI 没有可用 Docker 时该 Testcontainers 用例显式跳过；这只表示缺少 MySQL 9 验证环境，不允许自动降级为另一数据库并宣称同等通过。

### 回退与运维

- 表和列变更均为纯新增；V77 只给匹配当前群 / 知识库的一篇旧每日归档回填身份与版本元数据，不改正文、不删除聊天归档、图片、AI 会话或人生回顾数据。
- 业务回退时在内置菜单注册表中隐藏 `chat-reviews`，并在两个 API 节点关闭 `wao.chat-review.processing-enabled`
  (部署环境变量 `WAO_CHAT_REVIEW_PROCESSING_ENABLED=false`)。已存报告和原始归档保留，
  不通过回退 migration 删表或删数据。

## 后果

- 任一页面、设备或 API 节点都可读取同一条活动运行和历史报告；刷新和服务重启不会把运行变回浏览器内存状态。
- 每次重试或再次运行都增加一条记录，换取完整的运行历史和不覆盖旧报告；归档正文仍由知识库单份保存。
- 后台 worker 和只读 Codex 依然受外部飞书可用性、Runner 在线状态与槽位限制；不可用时页面展示持久化错误和重试入口，不用演示报告兜底。
- 来源文档后续被删除时旧链接可能失效，同一群同一天再次同步时链接会打开更新后的原文；但切换到另一个群会使用新的归档身份和独立文档，
  不会把旧报告的来源链接改指向新群内容。一期不为此复制聊天正文。
