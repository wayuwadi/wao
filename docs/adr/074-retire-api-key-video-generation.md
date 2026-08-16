# ADR-074：撤下需要平台 API Key 的视频生成

## 状态

已采纳，替代 ADR-073。

## 上下文

ADR-073 在自媒体 Agent 中接入了 OpenAI Videos API。该实现是真实调用，但必须单独配置
`AGENT_VIDEO_API_KEY`，不符合产品希望复用现有 Codex / ChatGPT 订阅登录、不给成员增加平台
API Key 和按量账单的目标。

OpenAI 官方文档明确区分两类身份：Codex CLI 的“使用 ChatGPT 登录”提供订阅访问，API Key
提供按量访问；面向一般 OpenAI API 调用仍应使用平台 API Key。Videos API 的创建和下载示例也都使用
`Authorization: Bearer $OPENAI_API_KEY`，没有公布可用 Codex 订阅 OAuth 调用 Videos API 的契约。
当前创建接口允许 4、8、12 秒，不接受需求中的 3 秒。因此，把 Codex OAuth 令牌转发给 Videos API、
自动化 Sora 网页或播放预制文件都不是可靠且诚实的实现。

依据：

- [Codex Authentication](https://developers.openai.com/codex/auth)
- [Videos API：Create video](https://developers.openai.com/api/reference/resources/videos/methods/create)

## 决策

- 撤下自媒体 Agent 的“视频生成”入口和 `/api/agent/videos/**` 接口。
- 删除 OpenAI Videos API 客户端、异步状态机、生成文件下载逻辑，以及
  `AGENT_VIDEO_BASE_URL`、`AGENT_VIDEO_API_KEY`、`AGENT_VIDEO_MODEL` 部署配置。
- 删除只为视频接口设置的 Linux 主节点 Nginx 路由，避免留下看似可用但始终要求额外密钥的入口。
- 不提供 3 秒预制 Demo、占位进度、本地动画或非公开 OAuth 适配器来冒充订阅视频生成。
- 已执行的 Agent Flyway V4 不修改、不删除，也不新增破坏性 migration；历史表与可能存在的数据原样保留，
  应用不再读写。若以后需要清理，必须在确认生产数据后另行授权。
- 将来只有在供应商公布订阅身份可用的官方程序接口，或产品明确选择并授权另一套真实视频服务时，
  才通过新 ADR 和适配器恢复视频生成。

## 后果

- 成员不会再被页面要求提供与 Codex 订阅无关的 API Key，也不会误以为订阅已经包含 Videos API 用量。
- 当前无法在 WAO 内生成或展示 3 秒视频 Demo；这是能力被诚实撤下后的预期结果，不算部署故障。
- 普通视频素材上传和发布能力保留，不受本次退役影响。
- V4 历史表暂时占用少量数据库结构，但避免修改已执行 migration 或未经授权删除生产数据。
