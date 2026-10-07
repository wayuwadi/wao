# 自主建设

## 目标与入口

浏览器 `/automation?tab=development` 关键节点工作台，`&run=<id>` 精确任务深链。首页、小人与看板复用成员隔离的同一份状态；不增加第二份队列，不用演示数据冒充后台任务。关注改什么、三方案首项推荐、Agent 分工、待确认、实际 PR 和发布证据。

## 状态与权限

仅已登录且被部署显式授权的成员或系统管理员可操作本人记录；授权固定成员不改变组织角色或工作区令牌。首次读取策略默认关闭；必须显式启用，并连接固定成员/WAO 仓库的本机执行器。审视间隔 1–168 小时，最多 1–3 个任务同时执行，每次间隔轮转一个功能。等待决定释放槽；同成员同功能未收口记录唯一。关闭策略阻止新领取与自动审视，当前运行继续安全收口。工作台支持失败恢复和 CAS 冲突提示，不覆盖本地草稿。

`QUEUED → ANALYZING → IMPLEMENTING → VERIFYING → PR_READY/MERGED → DEPLOYING → RELEASED`；没有真实问题 `NO_CHANGE`；大事 `WAITING_DECISION`；退回 `PAUSED`；失败或失租 `FAILED`。已上线仍等待用户验收。执行器 90 秒无心跳显示离线；2 分钟租约每 20 秒续期，旧 token 不能写阶段或终态。

Flyway V190 新增 development_policy/run/decision/worker，不修改历史迁移。决定绑定版本，7 天失效后转为可重试的 PAUSED；旧回复不能续跑，重试重新审视。CAS 策略和重试、决定行锁、唯一 active_key、领取策略行锁保证隔离与去重。输出只保存结构化目标、方案、分工与证据，不保存原始 CLI 输出和认证文件。

## HTTP 契约

全部为 `/api/development`，使用 `ApiResponse` 与当前成员会话。

| 请求 | 用途 |
|---|---|
| GET /overview | canManage、policy、worker、features、最近 100 个 runs |
| PUT /policy | enabled、version、intervalHours、maxParallel；旧版返回冲突 |
| POST /runs | featureId，建立/复用功能审视 |
| GET /runs/{id} | 精确读取本人运行，旧深链不得回退到另一条 |
| POST /runs/{id}/retry | version，保留半成品与证据恢复 |
| POST /decisions/{id} | revision、action approve/reject、非空 reply |

不增加公开 claim、shell、部署接口；不增加 Workspace MCP tools 和 CLI 执行命令。旧 token 的 48 项能力不扩权。此显式授权的代码执行协调需独立权限审计后才适合增加 CLI/MCP scope；当前状态入口沿用浏览器。

## 执行器

显式属性 `wao.development.worker-enabled=true`、`member-id`、`repository`、`directory`（仓库外私有目录）、`executable`（既有 Codex runtime）。可选 `delivery-directory` 仅接受此固定仓库下已有 `deploy/data/windows-local`，只在交付阶段允许写本执行器 Git 元数据和既有发布目录。私有目录保存独立 control clone、run worktree、Agent worktree、结果和 sessionId。禁止复制认证；进程环境不包含 DB/API secrets。Codex 使用 read-only / workspace-write，Windows 显式选择已配置的原生 elevated 沙箱；只读审视没有实际成功命令时拒绝采用模型结论。路径范围由外层校验，依赖循环拒绝，敏感路径与目录整体修改进入本人确认。单 Agent 超过 40 文件、单文件超过 500 KB、符号链接或私有运行值命中均停止汇聚。任务独立 Agent 只实现和检查，外层汇聚；验证阶段不发布，交付阶段不改变被测源码。无法获得真实验收通道则记录阻塞，不提前合并。

验收专用 `audit-only=true` 只审视未有方案的记录，保存后排队等待正式执行器；不得反复领取已有方案。`notifications-enabled=false` 可在本地验收期间关闭实际发信，正式执行默认开启。

恢复保存会话关联和 worktree，不使用 resume --last 或盲目重放。已有合并证据只核对 PR/公网版本；子工作区按 patch-id 查询未汇聚提交，避免重复 cherry-pick。退回或失效后的新审视保留旧工作区，子分支带决定版本，避免与旧 Agent 分支撞名。主干普通及 ADR/Flyway 编号冲突仍由交付检查，冲突后需修复并重新验收。PR 分支及 HEAD 必须匹配外层冻结的已测提交；公网版本须覆盖合并 SHA，静态资源按同版本制品清单校验 SHA-256，支持不可变 release 路径。

## 飞书决定

复用已加密的任务收集应用配置和已绑定账号 open_id。仅鉴权成功的 `/api/feishu/events` 进入路由。交互卡片按钮携带决定 id/revision/action；回调必须同时匹配 app_id/open_id/open_message_id 与有效账号。私聊只处理 user/text/p2p 且明确回复指定消息，回复“确认”或“确认继续”，退回可附理由。发送稳定 uuid 去重，真实 message_id 才标 SENT，有限重试失败仍可从浏览器回复。

飞书应用需已有发送私聊权限与 card.action.trigger / im.message.receive_v1 订阅。不能通过浏览器自动申请新权限；缺少订阅或权限时明确报告并保留浏览器决定入口。真实用户点击与飞书配置是否完整仍必须现场核验。

## 验收

核心测试覆盖成员隔离、关闭默认值、功能去重、策略 CAS、租约限制/失效回写、决定事务回滚/重复与消息身份、模糊回复、路径/依赖与沙箱参数。前端覆盖状态共享、草稿恢复、精准深链、失败重试、窄屏和真实 API 链路。发布核对实际 SHA 覆盖、资源和就绪；实体设备最终感受由用户验收。没完成真实执行/飞书回调/公网发布时必须分别报告缺项。
