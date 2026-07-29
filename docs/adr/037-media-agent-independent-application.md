# ADR-037：自媒体自动化 Agent 采用独立应用并嵌入 WAO

## 状态

已采纳，尚未实施。

## 上下文

自媒体自动化 Agent 会包含内容资产、自动化流程、模型调用、平台授权、发布任务和效果数据，业务模型、
迭代频率与运行负载都不同于 WAO 的项目、任务、知识库和 AI Runner。若继续放在 `wao-web` 与
`wao-server` 中，每次 Agent 页面或后端升级都会重新构建并部署整个 WAO，平台 SDK、媒体处理依赖和
高频任务也会扩大主站的故障范围。

另一方面，用户仍希望从 WAO 左侧菜单和顶部页签进入 Agent，沿用当前 WAO 身份，并保持统一的工作台体验。
因此需要同时满足“体验上嵌入”和“工程上独立”。

## 决策

- 新建独立 GitHub 仓库，默认命名为 `wao-media-agent`。仓库拥有自己的前端、后端、Worker、数据库迁移、
  文档与 CI/CD；仓库名调整不改变本 ADR 的边界。
- Agent 使用独立入口 `https://agent.wayuwa.cn`。WAO 只新增一个稳定的 `/media-agent` 壳页面，
  在现有菜单与顶部页签中打开跨源 iframe，并提供“在新窗口打开”的退路。
- 不采用 Module Federation、qiankun 或把 Agent 构建产物复制进 `wao-web`。Agent 日常发布不得触发 WAO
  构建；只有菜单、SSO 协议或跨应用交互发生变化时才修改 WAO。
- WAO 与 Agent 不共享 `WAO_SESSION` Cookie、Redis Session、签名密钥或业务表。WAO 通过有效登录会话签发
  60 秒有效、单次使用、绑定目标应用与浏览器握手随机数的一次性 SSO 票据；Agent 后端服务端兑换票据后建立
  自己的 HttpOnly Session。
- Agent 维护本地用户与权限，`waoAccountId` 只作为不可变的外部身份映射。Agent 不信任前端传入的
  `accountId`、`memberId` 或角色。
- 两个应用的数据默认完全隔离。Agent 需要 WAO 任务、成员或通知能力时，只能调用带版本的正式 API 或消费
  明确定义的事件，不得跨库查询、共用 MyBatis Mapper 或依赖 WAO 内部表结构。
- Agent 首版采用“模块化单体 API + 独立异步 Worker”，不为每个业务模块建立微服务。执行记录、发布状态与
  幂等键落 MySQL，队列和短时状态使用 Redis，图片、音频和视频使用 S3 兼容对象存储。
- 自动发布默认经过人工审核。平台没有正式接口、真实授权或可靠返回值时，必须明确显示“暂不支持”，不得用
  演示数据伪装成功发布或效果指标。

## 后果

- Agent 前后端可独立构建、回滚和扩容，普通 Agent 迭代不会重新部署 WAO。
- WAO 仍需完成一次菜单页签、iframe 容器、SSO 票据和安全策略接入；此后只有集成契约变化才需要再次发布。
- 跨源 iframe 会带来加载失败、浏览器弹窗、下载、深链和通信约束，必须提供独立打开入口，并对
  `postMessage` 做精确来源校验。
- 独立 Session 使两个应用不会因共享存储互相拖垮，但“退出 WAO 后 Agent 立即失效”需要短会话、敏感操作
  重新确认和后续的注销通知共同保证。
- 新增 Python Worker 与对象存储运维面；换来的是媒体处理、平台适配和 Agent 执行不进入 WAO Java 进程。
