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

Windows 受监督进程须使用实际存在的 Git / GitHub CLI 安装目录，不依赖桌面应用虚拟化后的表面路径；用原生 Java 子进程验证工具可执行性。已有 GitHub 登录可通过 `GH_CONFIG_DIR` 指向同一成员的既有安全配置目录，此变量只保存目录，不包含或复制 Token；专用开发进程保留该目录指针。部署不得为解决路径差异创建新凭据。

### 专用依赖与验收环境

`DevelopmentEnvironment` 在父/子 worktree 创建或恢复后准备前端依赖。依赖源默认是固定 primary `repository`；可选 `wao.development.dependencies-repository` 只接受 `directory` / state 内真实普通 Git clone（实际路径无越界链接、`.git` 位于 clone 内、origin 为允许的 WAO HTTPS/SSH URL）。此属性不改变业务 `repository` 或交付目录。运维可在该固定独立 clone 同步 main 后按其现有锁文件 `npm ci`，避免改动用户正在使用的 dirty 主仓库；执行器本身不安装、升级依赖或复制凭据。

Windows 仅当固定依赖源与目标工作区的 `wao-web/package.json`、`package-lock.json` **逐字节一致**，源 checkout 内部的现有 `node_modules` 可用且目标路径被 Git 忽略时，通过参数数组调用 `cmd /c mklink /J` 创建工作区内 junction。现有 `wao-game` 也按自身 manifest、源 checkout 内部依赖和 ignored 规则准备，避免前端类型检查缺少游戏源码依赖。源目录和目标目录校验实际路径；不安装或更新共享依赖，不覆盖已有错误目录。正确链接重试复用；缺失、不匹配、非 Windows 或越界均明确阻塞，目录与半成品保留。已有合并记录的恢复只核对 PR/公网，不要求无关依赖准备。

可选 `wao.development.validation-context` 指向专用 `directory` 内已有 UTF-8 普通文件（相对路径以 `directory` 为基准），实际读取最多 20,000 字节；外部路径、穿越、目录、越界 junction、非法 UTF-8 或超限拒绝使用。内容作为部署已配置验收通道的运维资料追加到非只读调用，不是授权来源，不扩大成员、冻结源码 paths、凭据、用户数据或网络监听范围。已有账号的正常登录、只读回环助手和既有浏览器工具须由运维准备；上下文不得写入 Git，也不得借此创建账号、Token 或绕过认证。

非只读 Codex 调用仅额外允许本次 `output-<agent>` / `verification` / `delivery` 产物目录写入（交付阶段原有受限 Git/发布目录规则不变）。独立 Agent 的冻结 paths 限制针对源码，允许读取外层准备的 ignored 依赖，并在自己的产物目录保存不入 Git 的检查脚本、截图与缓存；独立检查与汇聚后的统一真实浏览器验收分开。源码路径检查仍由外层执行。

共享 `node_modules` 不能用来存放 Vite 写缓存。已有验收服务器须为每个工作区/端口配置独立 `cacheDir`；新临时检查可在自己的产物目录生成脚本，调用已有 Vite `createServer({ root, configFile, configLoader: 'runner', cacheDir })`：`root`/`configFile` 指向目标 `wao-web`/原配置，`cacheDir` 位于本次产物目录。`runner` 避免默认配置打包写入共享 `node_modules/.vite-temp`。不改仓库 Vite 源配置、不授予 primary repository 依赖目录写权限。

统一 verification 在汇聚提交之后检查工作区 clean 且没有未完成 rebase/merge/cherry-pick，再 `fetch origin main`、`rebase origin/main`，确认 clean、重新校验依赖后才冻结 `testedHead`。冲突不自动 abort/reset，未提交改动或未完成操作保留现场并阻塞；不发布未在新主干基线上验收的提交。验证与交付后仍核对 HEAD 和源码未改变。失败重试不删除工作区或产物。

恢复保存会话关联和 worktree，不使用 resume --last 或盲目重放。已有合并证据只核对 PR/公网版本；子工作区按 patch-id 查询未汇聚提交，避免重复 cherry-pick。退回或失效后的新审视保留旧工作区，子分支带决定版本，避免与旧 Agent 分支撞名。主干普通及 ADR/Flyway 编号冲突仍由交付检查，冲突后需修复并重新验收。PR 分支及 HEAD 必须匹配外层冻结的已测提交；公网版本须覆盖合并 SHA，静态资源按同版本制品清单校验 SHA-256，支持不可变 release 路径。

## 飞书决定

复用已加密的任务收集应用配置和已绑定账号 open_id。仅鉴权成功的 `/api/feishu/events` 进入路由。交互卡片按钮携带决定 id/revision/action；回调必须同时匹配 app_id/open_id/open_message_id 与有效账号。私聊只处理 user/text/p2p 且明确回复指定消息，回复“确认”或“确认继续”，退回可附理由。发送稳定 uuid 去重，真实 message_id 才标 SENT，有限重试失败仍可从浏览器回复。

飞书应用需已有发送私聊权限与 card.action.trigger / im.message.receive_v1 订阅。不能通过浏览器自动申请新权限；缺少订阅或权限时明确报告并保留浏览器决定入口。真实用户点击与飞书配置是否完整仍必须现场核验。

## 验收

核心测试覆盖成员隔离、关闭默认值、功能去重、策略 CAS、租约限制/失效回写、决定事务回滚/重复与消息身份、模糊回复、路径/依赖与沙箱参数；环境测试覆盖 manifest 不匹配、ignored/source 检查、正确 junction 幂等、错误目录保留、上下文实际字节限制/UTF-8/越界、单次产物写权限以及同步主干/dirty/rebase 冲突保留。前端覆盖状态共享、草稿恢复、精准深链、失败重试、窄屏和真实 API 链路。发布核对实际 SHA 覆盖、资源和就绪；实体设备最终感受由用户验收。没完成真实执行/飞书回调/公网发布时必须分别报告缺项。
