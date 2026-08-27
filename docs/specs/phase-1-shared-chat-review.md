# Phase 1：娃与娃聊天复盘

决策见 [ADR-088](../adr/088-shared-chat-review-workflow.md)，聊天页融合入口与共享消息范围见
[ADR-130](../adr/130-integrated-shared-chat-review-and-polish.md)。

## 目标与范围

新增共享内置模块 `chat-reviews`，路由为 `/chat-reviews`。成员选择上海时区的起止自然日，可选填写“本次重点”，点击一次
“同步并生成复盘”后，系统固定执行以下链路：

1. 重新同步范围内每一天的当前唯一飞书群聊；
2. 核对每日归档，从同批消息中只提取已固定的两位发言人；
3. 使用现有本地 Codex 只读队列生成四段、有日期证据的报告；
4. 保存运行参数、阶段、来源元数据和报告，所有已登录成员共享查看。

一期只支持当前飞书同步配置指向的一个群、两位固定发言人和日期级范围。不支持多群、分钟级范围、公开分享、报告编辑、OCR、
语音转写或回复关系分析，不创建通用工作流编辑器。

## 聊天页融合入口

- 默认导航不再展示独立“聊天复盘”；聊天页“复盘”按钮可选择最近 5～100 条真实消息或最近 1～168 小时全部消息。
- `POST /api/chat-reviews/shared` 只接受 `rangeKind / rangeValue / focus`，创建时把命中的消息 id 固化；最多 500 条，完整动态资料仍受
  60,000 字门禁，不截断、不抽样。
- 共享聊天复盘不重新拉取飞书归档，也不要求两位固定发言人；它读取 WAO 已持久化的共享消息，保留真实作者和顺序。图片与语音只作占位。
- 成功后必须同时产生 `/api/pages/ai-run-<runId>` 托管报告与“聊天复盘”知识库文档，详情返回两个真实入口；首页 AI 列表以
  “聊天复盘”标签展示活动和近期档案。
- 手动复盘可选择本地 Codex、已启用 Codex 账号、DeepSeek API Key 或受信模型网关；运行、重试和再次运行固化所选能力，
  不因默认配置变化而静默切换。非 Codex Provider 只能执行 ADR-137 定义的无工具服务端只读请求。
- 同一复盘面板可保存全局自动策略：启停、5 分钟～7 天间隔、最近 5～100 条消息或 1～168 小时、关注重点和 AI 能力。
  调度以数据库时间和到期 CAS 跨节点去重，页面展示下次计划时间且不会用轮询覆盖尚未保存的本地编辑。
- 首页“最近 AI 运行”以真实终态数据统一分页，支持全部、功能开发和聊天复盘筛选；开发卡仍保留完整运行详情，复盘卡打开报告。

## 首次配置与共享权限

- 页面进入后读取 `GET /api/chat-reviews/config`。群名、群 id 和归档知识库来自服务端当前飞书同步配置；可选发言人只来自当前
  `feishu.chat-sync.senders` 已配置成员映射。前端不写死“娃哥 / 娃弟”或任何 open_id。
- 未设置时页面展示明确引导。成员必须选择恰好两个不同的群成员；`PUT /api/chat-reviews/config` 仅接受
  `{speakerIds:[openId1,openId2]}`。保存时服务端用 open_id 重新校验两人仍存在于上述发送者映射，再把群与两人保存到共享配置
  `system_setting` 的 `feishu.chat-review.speakers` 键。
- 配置保存请求体必须恰好是一份 JSON 对象；空正文、重复键、首个 JSON 值后的尾随 token、非法 JSON、缺失或额外字段都返回 400，且不得落入部分配置。
- `GET /api/chat-reviews/config` 和 `PUT /api/chat-reviews/config` 都不实时调用飞书成员 API；候选人的新鲜度由既有飞书同步配置维护，
  避免打开或保存配置时新增长网络依赖。
- 飞书同步配置的 App、群、知识库和发送者映射必须作为一组保存与读取：保存多个 `feishu.chat-sync.*` 键使用一个数据库事务；配置读取使用一次批量 SQL
  取得同一事务快照，复盘不得组合逐键查询得到的新旧混合配置。
- 若飞书同步尚未配置 App、唯一群或知识库，或真实可选成员不足两人，不返回演示选项，保存和发起接口都给出可理解错误。
- 当前同步群或发言人映射变更后，已存报告仍按运行快照展示；新运行禁用，直到成员重新确认两位对象。
- 所有已登录 WAO 成员都可以读取和修改这份配置，也可以发起、重试、再次运行和查看全部运行。页面显示报告创建人，但不将其作为权限过滤。

## 参数与提交

- 日期默认为“上海当天及往前 6 天”，共 7 个自然日。`startDate` 与 `endDate` 都包含在范围内，起始不得晚于结束，总天数不得超过 31，
  两者都不得早于 `1970-01-01`，`endDate` 不得晚于 `Asia/Shanghai` 的当天。
- `focus` 可省略，但显式传 `null` 或其他非字符串类型按 400 处理。去掉首尾空白后的空字符串按未填处理，非空时最多 1,000 字。它只改变 AI 的关注点，
  不能改变四段结构、证据范围、引用规则或安全指令。
- 请求体必须恰好包含一份 JSON 对象，并且只允许 `startDate`、`endDate`、`focus` 三个字段。空请求体、重复键、首个 JSON 值后的尾随 token、非法 JSON、
  缺必填字段、类型错误和额外字段都返回 400，不创建半条运行。
- 提交成功只创建一条持久化 `WAITING` 运行，并同时快照 `chat_id`、`kb_id`、群名、两位发言人、日期和重点，返回 HTTP 202 和 `runId`；请求不等待飞书或 Codex。
  全局已有活动运行时返回 409，该错误响应不额外承诺 `activeRunId`。
  页面重新读取 `GET /api/chat-reviews` 后跟踪已有活动运行，不伪造第二条队列记录。

## 阶段、恢复与并发

| 阶段 | 页面文案 | 含义 |
|---|---|---|
| `WAITING` | 等待执行 | 参数已保存，等待后台 worker 获得数据库租约 |
| `SYNCING` | 同步聊天 | 按日重新拉取、覆盖归档，进度分母是范围全部日期 |
| `VERIFYING` | 核对资料 | 核对归档、精确过滤两人、排序、统计与检查完整动态 JSON 的 60,000 字上限 |
| `ANALYZING` | AI 分析 | 只读 Codex 已入队或正在执行，页面查询而不占用长 HTTP 请求 |
| `SUCCEEDED` | 报告已保存 | 四段结构和全部引用已通过服务端校验 |
| `FAILED` | 失败 | 保存失败阶段、可读错误，同步失败时还保存具体日期 |

- `WAITING / SYNCING / VERIFYING / ANALYZING` 是活动阶段。一期只有一个配置群，数据库生成列在活动态固定为 `active`、终态为 `NULL`，唯一索引保证全局最多一条活动运行；
  两个 API 节点同时提交也只能成功一个。即使中途切换群或知识库配置，旧活动运行仍占用该全局名额，直至进入终态。
- worker 使用数据库 lease 的条件更新抢占活动运行，不依赖单进程锁。每次阶段、进度、来源、报告和错误写入都用 `worker_token + 未过期 lease_until + 活动阶段`
  做 fencing；claim、续租和 `aiRunId` 关联均以 MySQL `CURRENT_TIMESTAMP(6)` 判断和延长租约，不使用 JVM 时钟。影响 0 行时旧 worker 立即停止。
  AI 运行入队和关联在同一事务内使用同一租约栅栏，关联失败整体回滚。节点崩溃或部署导致 lease 过期后，其他节点可接管。
- 运行的 `createdAt / startedAt / finishedAt` 分别由 MySQL `CURRENT_TIMESTAMP(6)` 在插入、首次受栅栏的 worker 写入和终态写入时签发，不用 API 节点时钟排历史。服务端把数据库的上海本地时间转为 RFC 3339 `+08:00` 后返回。
- 恢复同步阶段时可从第一天重新执行，因为同一知识库、群 id 与自然日组成稳定归档身份，并由数据库归档版本 CAS 决定谁能覆盖正文。恢复分析阶段时，若 `aiRunId` 已存在则只查询原 AI 运行，不重复入队。
- 全范围同步完成后进度保持 `N/N`；`VERIFYING`、`ANALYZING` 和 `SUCCEEDED` 沿用这个日期覆盖进度，不改成 AI 子阶段的 0/1 或 1/1。
- 页面轮询 `GET /api/chat-reviews/{id}` 恢复进度；刷新、换设备和换 API 节点后都以数据库状态为准。轮询不修改未提交的日期或“本次重点”草稿。

## 同步、资料核对与输入边界

- 后台从 `startDate` 到 `endDate` 逐日调用现有同步能力。每日仍显示在 `YYYY 年 M 月` 目录中，标题为 `飞书群聊 · YYYY-MM-DD`，但实际幂等身份固定为
  `(kb_id, auto_sync_scope = chat_id, auto_sync_date = 日期)`；同一群同一天重试覆盖同一文档，切换群后同一天必须创建独立文档，标题不再充当跨群幂等键。
- 复盘使用内部 silent 同步，不逐日发送飞书沉淀通知。一期也不额外发送复盘完成通知，避免用另一条通知弥补逐日刷屏。
- 任意一天同步失败后立即停止后续日期和 AI 分析，运行转为 `FAILED`，页面同时展示失败日期和飞书 / 归档错误。
- 运行执行前及每天同步内部都把当前群和知识库与运行快照的 `chat_id / kb_id` 比较；任一变化都失败，不把旧运行写入新知识库。飞书 HTTP 连接超时为 10 秒、
  读取超时为 30 秒；日边界、消息渲染和图片处理期间定期执行 lease heartbeat，使用数据库时钟续租，租约丢失后停止。
- V77 为 `doc` 增加 `auto_sync_scope`、`auto_sync_date`、`auto_sync_version` 和 `(kb_id, auto_sync_scope, auto_sync_date)` 唯一索引。
  migration 只用执行当时 `system_setting` 的 `feishu.chat-sync.chat-id / kb-id` 回填旧归档：在当前知识库内匹配 `auto = 1` 与 `飞书群聊 · YYYY-MM-DD` 标题，
  每个知识库 / 标题优先选未软删除候选中的 `MIN(id)`；仅当所有候选都已软删除时，才选全部候选的 `MIN(id)`。被选中文档写入群 id、标题日期和版本 0；
  历史重复标题的其余文档与其它未匹配文档保持无身份，运行时永不被任何群猜测或认领。
- 归档事务先锁定知识库父行并按稳定身份查找，查询包含软删除文档。精确身份存在时原地清除 `deleted_at / deleted_by / cascade_del`、移到目标月份目录并更新正文，继续复用原 `docId`；
  身份不存在时才新建。这样软删除后重同步不会留下可恢复的第二篇同身份文档。
- 每次向飞书发起消息查询前，先在事务中锁定 `feishu_chat_archive_sequence` 单行并签发跨节点单调版本。新建文档时在归档事务内初始化身份与版本；更新时
  `DocMapper` 只有在身份精确相同且传入版本更大时才能原子 CAS 覆盖正文。旧查询晚到时该日同步失败，不得把旧消息与已由新查询更新的文档 id 组成来源证据。
  新群不得领用任何无身份旧文档或已标记为旧群身份的文档，因此历史报告的来源链接不会被新群同日内容覆盖。
- 稳定身份不把归档变成只读文档。通过文档 `PATCH` 更新一篇 scoped 归档的标题、正文、链接或所在目录时，服务端先从同一 `feishu_chat_archive_sequence` 签发新版本，再使用专用 CAS 按文档 id、scope、日期和读取时版本一次更新可编辑字段及 `auto_sync_version`。
  同步或另一次 `PATCH` 已先胜时返回 409，页面刷新后才可重试。已签发的 `PATCH` 修改拒绝更旧同步快照，但之后签发更大版本的新同步仍可正常覆盖自动归档内容。文档树拖拽只窄更新 `folder_id / ord`，不更改正文、链接或归档版本，不签发新版本；下一次自动同步可把该归档放回固定月目录。
- 同步内部结果同时交给归档写入和复盘核对。消息必须同时满足飞书原始 `senderId/open_id` 与本次运行快照精确匹配以及
  `sender_type == user`，不通过显示名或文本正则推测身份。机器人即使因错误配置而使用了已选 id，也必须被硬过滤；
  被删除消息、第三位成员和未匹配发言人的正文同样不得进入 AI 材料。
- 两个不同 `open_id` 按配置顺序稳定映射为“对象A”和“对象B”。AI 参数分别传递 `label` 与姓名，消息发言人显式写成
  “对象A（姓名）”或“对象B（姓名）”。两人展示名相同时仍以 A / B 区分，不得用姓名作 key 合并消息。
- 筛选后的消息先按日期、发送时间升序，时间仍相同时按消息 id 排序。全范围同步成功并进入核对后，所选范围每个日期都按升序编为 `S1…Sn`，
  并保存最新归档元数据；
  无目标消息时该来源的 `messageCount` 为 0。因此来源清单和同步进度都能核对范围内全部日期。
- 只把 `messageCount > 0` 的来源及其消息放入 AI 动态材料。若整个范围的每日来源均为 0，运行在 `VERIFYING` 失败。
- 文本、富文本和可提取卡片保留现有可读文字。图片仅显示 `[图片]`，语音仅显示 `[语音]`，视频显示 `[视频] + 文件名`，文件显示 `[文件] + 文件名`。
  占位仍关联原消息的发言人和时间，但页面与报告不声称已完成图像识别、视频理解或语音转写。
- 知识库归档仍按现有能力把飞书图片转存图床。该路径必须以 `deduplicate = true` 写入图片正文 SHA-256 `dedupe_key`；V77 的唯一键保证多节点或重试并发写同图时只保留一份
  LONGBLOB，唯一冲突方复用已插入的赢家 id。用户从图床主动上传时 `dedupe_key` 保持 `NULL`，不得因聊天同步去重改变普通上传允许同图多份的语义。
- AI 只接收服务端组装的参数 JSON 和 `messageCount > 0` 的来源消息 JSON。参数 JSON 包含群名、起止日期、“对象A / 对象B”稳定标签、各自名称和 `focus`；来源 JSON 包含编号、日期、消息时间、带对象标签的发言人和正文。
  两个完整序列化字符串的 Unicode code point 总数不超过 60,000，计数包含 `focus`、日期时间、姓名、字段名、引号等 JSON 开销；超限或整个范围没有有效目标消息时，明确提示缩小范围，
  不静默截断、不抽样、不调用 Codex。

## AI 报告与服务端验证

- 资料和“本次重点”均用 JSON 字符串封装为不可信引用，提示中的固定规则由服务端组装且浏览器不能改写；该语义分隔只属于纵深防御，不能把“模型会听话”当作
  提示注入隔离机制。
- 聊天复盘复用页面助手的无工具执行档位：Runner 为每次运行新建临时空 Git 仓库和独立临时 `CODEX_HOME`，以 `read-only` sandbox 调用 Codex，并固定传入
  `--ephemeral`、`--ignore-rules`、`--disable shell_tool`、`--disable multi_agent`、`tools.view_image=false`、`web_search=disabled`。
  因此不可信聊天不能通过 Shell / 本机文件、图片、联网检索或子代理工具扩大读取与执行范围。
- Runner 启动该 Codex 子进程前剥离 `GH_TOKEN`、`GITHUB_TOKEN` 及同类 GitHub Token；聊天复盘不需要也不得继承开发任务的仓库写入或 GitHub 权限。
  旧 Codex CLI 不识别任一安全开关时必须非零失败，不得去掉开关重试或降级成带工具模式。
- Codex 非零退出时，Runner 丢弃 stdout / 最后一条消息中的部分报告，只回传稳定失败类别与退出码；服务端不得把部分输出写成 `response_text` 或 `reportJson`，
  只保存有界稳定错误。关联的 `ai_assistant_run` 进入 `succeeded / failed` 或超时终态时，不论成败都立即将 `prompt_text` 显式置空；报告验证后只保留四段结果与每日来源元数据。
- 聊天复盘的内部只读运行以 `secure_queued` 入队，被领取后转为 `secure_running`。Runner 只使用独立的
  `POST /api/ai/runner/assistant-v2/claim` 与 `POST /api/ai/runner/assistant-v2/{runId}/complete`；新 API 不保留旧 `assistant` 路由，新 Runner 遇到旧 API 的 v2 404 只重试 v2，绝不回退旧协议。
- 聊天复盘调用的是上述非阻塞 durable 入队；现有 `runReadOnlyJob / executeReadOnlyJob` 同步兼容包装另用普通 `queued / running` 并保留五分钟调用窗口。两者共用 assistant-v2 的无工具 Runner，但只有非阻塞 `secure_*` 按下述 60 / 30 分钟数据库硬超时恢复。
- 入队时不查当下是否有在线且脚本哈希兼容的 Runner；当前 API 构建必须能读取自带的安全 Runner 脚本，内部提示也必须合法且同一操作没有活动运行，才持久化 `secure_queued` 并让复盘进入 `ANALYZING`。兼容节点后续上线可领取；始终没有节点时，该运行在默认 60 分钟的 `secure_queued` DB 硬超时中失败并清空 prompt。
- 只有心跳上报的 Runner `scriptHash` 与当前 API 构建中打包脚本的原始字节 SHA-256 完全相同，且节点具备 Codex 能力时，服务端才允许领取。未打包脚本、未上报或哈希不一致均返回无任务。
  旧 Runner 不调用 v2 且无法通过哈希门禁，旧 API 不识别 v2；滚动发布期间两种不匹配均 fail-closed，安全任务等待兼容节点或数据库硬超时，不使用旧协议或带工具模式。
- v2 claim 每次都签发新 `claimToken`，并返回由执行硬超时扣除保护余量得到的 `maxRuntimeSeconds`（默认 1,740 秒）。Runner 用本地单调时钟设定截止时间，超时即停止 Codex 并丢弃部分输出；
  complete 必须带回 token，服务端仅在运行 id、Runner id、token 与 `secure_running` 全部匹配时 CAS 接受结果。终态清空 token，旧 attempt 迟到提交无法覆盖终态。完成回传后的 `finally` 无论任务成功、失败、超时或 v2 回传失败，都清理临时空仓库和运行级 `CODEX_HOME`。
- 内部只读 `ai_assistant_run` 的入队时间 (`created_at / updated_at`)、领取时间 (`claimed_at / updated_at`) 和终态时间 (`finished_at / updated_at`) 全部由
  MySQL `CURRENT_TIMESTAMP(6)` 写入，不接受 API 节点或 Runner 的本地时间。超时条件同样由数据库以 `DATE_SUB(CURRENT_TIMESTAMP(6), ...)` 比较这些时间列。
- 内部只读 AI 队列必须由后台周期清理超时，而不是等页面或聊天复盘 worker 再次查询才处理：`secure_queued` 自创建起默认超过 60 分钟、`secure_running` 自领取起默认超过 30 分钟即以活动状态 CAS 为
  `failed`，保存有界稳定超时错误并清空 `prompt_text / claim_token`。阈值分别由 `wao.ai.read-only.queued-timeout` 和 `wao.ai.read-only.running-timeout` 配置；
  `secure_running` 不走页面助手的 3 分钟 JVM stale requeue，而是只由 MySQL 时钟的 30 分钟硬超时终止为失败，不再入队重复执行。清理与 Runner 完成竞争时只有一个终态更新成功，已超时运行不得被迟到完成复活。
- Codex 只能返回一个 JSON 对象，且恰好包含以下四个字段，每个都是 1–6,000 字符的非空字符串：

  - `discussionProcess`：讨论如何展开、分歧如何收敛；
  - `discussionDirections`：实际探索过的方向与取舍；
  - `conclusions`：必须显式分成“已明确：”、“暂定：”、“未解决：”三个非空分类；没有证据支持的类别也要写“未见明确记录”等非空事实说明；
  - `recommendations`：必须以“AI 建议：”或同名 Markdown 标题开头并紧接非空内容，不冒充双方已达成的决定。

- `discussionProcess`、`discussionDirections` 和 `conclusions` 每段至少包含一个 `[S编号]`。四段中出现的所有 `[S编号]` 都必须存在于当次来源清单，
  且对应来源的 `messageCount > 0`；服务端拒绝引用 0 消息日期。
- 三个结论分类可以从整个字符串开头、中文句号或中英文分号后的句界、或换行开始，也可以是 Markdown 列表项或标题；每个标签后必须有中英文冒号和非空正文。
  只在叙述中提到“已明确、暂定、未解决”三个词不算通过。建议字段只允许在整个字段开头使用“AI 建议：”标签 / 同名标题，末尾补写“AI 建议”不算通过。
- 服务端拒绝非法 JSON、根节点非对象、字段缺失、额外字段、错误类型、空字符串、超长字段、缺少引用、越界引用、结论分类缺失和建议标记缺失。
  四段中任何 Markdown 图片 / 链接、URL、HTML 或引用式链接定义也必须被拒绝，避免页面渲染模型输出时向外站发起请求。
  任意一项失败都把运行记为 `FAILED`，不写入部分 `reportJson`，不使用静态文案补洞。
- 报告来源清单覆盖所选范围的每个日期，只保存编号、日期、知识库 / 文档 id、站内链接和过滤后消息数，其中消息数可为 0。
  日期模式聊天正文只存在知识库归档；共享聊天模式只保存消息 id 快照，不向 `chat_review_run` 复制正文。

## 历史、重试和再次运行

- `GET /api/chat-reviews` 不按当前 `chatId` 过滤，返回全局活动运行与最近 60 条终态历史。切换群或知识库配置后，旧活动运行仍可恢复查看，且在其终态前新建运行继续返回 409。
  失败尝试保留错误和重试入口；成功运行即可切换阅读的历史报告。
- 失败运行的“重试”调用 `POST /api/chat-reviews/{id}/retry`，请求体必须为空；新建一条运行，并在数据库用 `retry_of_id` 指回原失败记录；该内部关联不增加到公开响应字段。
  重试复制原群、知识库、两人、日期和重点快照，完整保留原失败记录，
  从第一天重新同步。若当前飞书同步群或知识库与原快照任一不同，拒绝重试并要求用当前配置新建运行。
- 成功报告的“按原参数再次运行”调用 `POST /api/chat-reviews/{id}/rerun`，只接受 `SUCCEEDED` 运行且请求体必须为空。`retry / rerun` 的任何非空正文（包括 `{}`）都返回 400。
  服务端复制原运行保存的
  群、知识库、两位发言人、日期和重点快照并返回 `202 + runId`；当前飞书同步群或知识库与原快照任一不同时返回 400。全局已有活动运行时返回 409，旧报告不更新也不删除。
- 每份成功报告支持复制 Markdown。复制内容固定包含四个标题及正文，并在末尾列出 `S编号 + 日期 + 原聊天文档链接 + 消息数`。复制失败显示明确重试入口。

## 页面状态与验收

- 页面覆盖配置未完成、配置加载与保存、日期 / 重点草稿、提交中、六个运行阶段、进度、失败日期、重试、历史空状态与切换、复制成败、来源跳转和数据加载失败重试。
- 活动运行的服务端参数与本地未提交表单分开展示。轮询、历史切换、配置刷新和另一设备发起的运行都不覆盖用户尚未提交的日期与重点。
- 窄屏下配置、日期与重点、阶段进度、四段报告、来源和历史改为单列；长标题、长错误和引用不产生业务区水平滚动，主操作始终可见。
- 后端单元 / 集成测试覆盖：`1970-01-01` 下界、上海日期边界、31 天限制和未来日期；两个 open_id 与 `sender_type == user` 双重过滤、误选相同 id 的机器人 / 其他成员排除、稳定排序，以及两个不同 open_id 同名时仍用对象 A / B 区分；
  无记录与包含参数、姓名、日期时间及序列化开销的 60,000 字超限；飞书配置事务保存和单 SQL 快照读取、群 / KB 运行快照、归档版本单调签发；
  migration 按当前群 / KB 回填旧归档，重复标题优先存活文档 `MIN(id)` 且仅全删时回退全体 `MIN(id)`、运行时不认领无身份文档、唯一身份并发、软删除原地恢复、切群同日独立文档、Doc CAS 与旧快照拒绝，以及 scoped 归档文档 `PATCH` 签发全局新版本、专用 CAS 及并发失败 409；文档树拖拽只更新 `folder_id / ord`、不回写旧正文或归档版本，且后续同步可恢复固定月目录；
  飞书图片去重写的 SHA-256 唯一冲突复用、普通上传 NULL 不去重与并发下只落一份 LONGBLOB；
  10 秒连接 / 30 秒读取超时、长同步 lease heartbeat、中途日期同步失败、全范围重试幂等、成功运行按快照再次运行、非成功状态拒绝、群或 KB 变化拒绝与旧报告保留；
  MySQL 数据库时钟的 lease claim / renew / link、复盘创建 / 首次开始 / 终态时间、`+08:00` 接口序列化、接管和旧 worker fencing、AI 关联回滚、全局活动运行抢占、切配置后旧活动可见和共享可见性；
  `secure_queued / secure_running`、无兼容 Runner 时仍可入队并由 60 分钟 DB 超时收口、精确脚本哈希能力门禁、旧 Runner / 旧 API 滚动发布 fail-closed、独立 assistant-v2 claim / complete、`maxRuntimeSeconds` 与单调截止、临时目录必然清理、AI claim token 与迟到完成 CAS 拒绝，
  以及安全运行不经 3 分钟 JVM stale requeue、只经 DB 30 分钟硬超时的终态竞争；
  空请求体、重复 JSON 键、尾随 JSON token、非法 JSON、缺失 / 额外字段、无体动作携带正文、越界引用、三个非空结论分类的句界 / 列表 / 标题形式、
  建议字段开头的“AI 建议：”/ 同名标题、提示注入和 AI 终态提示正文清空。
- MySQL 集成验收使用 `mysql:9.0` Testcontainers；Docker 可用时执行 Flyway V1–V76、预置同标题存活 / 软删重复旧归档后迁到 V77，验证优先存活 `MIN(id)` 与全删回退，并在真实 MySQL 上覆盖全局活动唯一抢占、数据库 lease、
  归档身份 / CAS / 软删复用、AI 入队关联丢失时事务回滚，以及数据库时间驱动的 `secure_*` 超时清理。Docker 不可用时通过 `disabledWithoutDocker` 显式跳过，不能改用 H2 冒充 MySQL 9 验收。
- Runner 验收覆盖：只读分析使用临时空仓库、`read-only` 与全部强制无工具开关；环境剥离 GitHub Token；正常、非零、单调时钟超时及 v2 回传失败时均清理临时仓库和 `CODEX_HOME`；恶意聊天要求读取本机、联网、
  调用子代理或 GitHub 时仍无对应工具能力；旧 CLI 不支持开关时失败关闭且不降级；非零退出的部分报告不回传，服务端只落稳定错误。
- 前端测试覆盖：配置缺失；同步 / 核对 / AI 进度；刷新恢复；失败日期和重试的新 run id；成功报告按快照再次运行的新 run id；历史切换；Markdown 复制；来源跳转；
  390px 窄屏；轮询不覆盖未提交输入。
- 真实飞书端到端验收必须确认：范围内每日知识库文档已覆盖更新；群里没有逐日沉淀通知刷屏；四段报告通过有效引用校验；两个真实账号能看到同一运行与报告；
  每个来源链接都能回到对应群和日期原文；切换配置群后同步同一天不会改变旧群报告的来源文档内容或链接。

## V77 首次发布

- 不用 MySQL trigger 兼容旧二进制；这会引入 binlog / trigger 权限、`DEFINER` 以及与应用回滚不能事务化协调的额外风险。包含 V77 的首次生产发布必须进入一次性安全维护窗口。
- 每次发布先调用 `rollout-status`；只有 Flyway V77 成功记录和 `system_setting` 中 `deployment.v77.rollout-state = complete` 同时存在时才走常规滚动发布，否则都继续 V77 安全窗口。门禁不只根据某一次 push 的文件列表判断，该发布也不允许被新 push 取消。流水线先将 master / slave 两个 Nginx upstream 都设为 down，并在修改 job 前确认从节点可达。随后以 `INSERT IGNORE` 将飞书聊天同步 automation 最初状态保存到 `deployment.v77.feishu-sync-status`，写入 `deployment.v77.rollout-state = in-progress` 后暂停它，并通过
  `quiesce-wao-server.sh` 及 Windows 包装脚本停止所有旧 `wao-server`。从节点不可达或无法确认已停止时 fail-closed，不执行 V77 migration。
- 新节点启动时才由 Flyway 执行 V77。只有新二进制健康的节点可重新入流；发布失败后脚本自动回滚出的旧容器必须再次停止，在 rollout 标记未完成前不恢复 master 流量。全新节点成功后调用 `complete-v77-rollout`；它要求 Flyway V77 成功记录、automation 状态保存键和目标 job 均恰好存在，才在同一事务中恢复原状态、写入 `deployment.v77.rollout-state = complete` 并删除 `deployment.v77.feishu-sync-status`。completion 后 CI 必须再调用 `rollout-status`，仅当 Flyway 记录和 marker 仍同时完成时恢复 master 流量；任一前置缺失或复核失败时保持 marker 未完成或全量摘流，修复后整体重试该事务动作。
- CI 脚本测试必须覆盖 automation 状态的 `INSERT IGNORE` 保存与事务恢复、Flyway 记录 + marker 双条件判定、两个节点停服、不可取消与持久 rollout marker、从节点无法停服时不迁移、旧容器回滚后再停止，以及未完成时 master 始终 down。操作步骤见
  [Phase 0 实机上线清单](../runbooks/phase-0-rollout.md#v77-首次发布安全窗口)。

## 回退

- 内置菜单注册表支持把 `chat-reviews` 设为不可见；直达页面不应因菜单隐藏而删除数据。
- 在两个 API 节点关闭 `wao.chat-review.processing-enabled`
  (部署环境变量 `WAO_CHAT_REVIEW_PROCESSING_ENABLED=false`) 后不再领取新运行。运行表、已生成报告、飞书归档和图片都保留；
  不删表、不删归档、不修改已执行 migration。
