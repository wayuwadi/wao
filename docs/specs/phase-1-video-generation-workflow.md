# Phase 1：真实视频生成与站内预览

## 目标

已登录成员可在“自媒体 Agent → 视频生成”中保存提示词草稿、提交真实 OpenAI Videos API
任务、观察异步进度，并在生成完成后直接播放持久化的 MP4。页面不得把文案生成、Codex OAuth
或演示文件冒充视频生成能力。

决策见 [ADR-073](../adr/073-openai-video-generation-sunset-adapter.md)，部署边界延续
[自媒体自动化 Agent 技术架构](../architecture/media-automation-agent.md)。

## 用户链路

1. 页面读取当前工作区最近 20 条视频任务；空数据时说明尚未生成，不插入样例视频。
2. 提示词在浏览器本地自动保存；提交成功后才清除草稿。
3. 用户选择官方支持的模型、时长和画幅后提交，后端先落库，再使用稳定幂等键调用上游。
4. 页面轮询显示排队、生成、下载、完成或失败；失败任务保留原参数和错误，可明确重试。
5. 上游完成后，Linux 主节点立即下载 MP4 到 Agent 媒体卷并登记为真实 `agent_media_asset`。
6. 页面通过带 WAO Session 鉴权的同域接口播放；无权访问其他工作区的任务或文件。

## 能力与约束

- 使用 OpenAI 官方 `POST /v1/videos`、`GET /v1/videos/{id}` 和
  `GET /v1/videos/{id}/content`，API Key 只来自节点私有环境变量 `AGENT_VIDEO_API_KEY`。
- Codex 设备码 OAuth 属于订阅式 CLI 身份，不能当作 OpenAI API Key 复用。
- 当前允许 `sora-2`、`sora-2-pro`，时长为 4/8/12 秒，画幅为
  `720x1280`、`1280x720`、`1024x1792`、`1792x1024`。
- OpenAI 已公告 Videos API 与 Sora 2 系列于 2026-09-24 停用。页面必须展示停用日期；到期后
  后端自动停止接受新任务，但保留已下载视频的查看能力。
- 视频生成与播放路径固定到 Linux VPN 主节点，避免 Windows 节点没有 OpenAI 出口以及本地媒体卷
  跨节点不可见。主节点不可用时明确失败，不回退到伪结果。
- 未配置 API Key 时返回 `available=false` 并禁用提交；不得生成样例、占位视频或伪造进度。

## 状态与恢复

状态依次为 `SUBMITTING → QUEUED/IN_PROGRESS → DOWNLOADING → COMPLETED`，任一步可进入
`FAILED`。任务、上游标识、进度、错误、重试次数、媒体标识与时间均持久化；服务重启后继续处理
`SUBMITTING`/`QUEUED`/`IN_PROGRESS`，停在 `DOWNLOADING` 的任务也会重新下载。

## WAO 接口契约

所有响应除媒体内容外都使用 `ApiResponse<T>`，且都要求有效 `WAO_SESSION`：

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| `GET` | `/api/agent/videos` | 返回 `capability` 与当前工作区最近 20 条 `tasks` |
| `POST` | `/api/agent/videos` | 接收 `prompt/model/seconds/size`，落库并提交真实任务 |
| `POST` | `/api/agent/videos/{taskId}/retry` | 仅重试当前工作区的失败任务 |
| `GET` | `/api/agent/videos/{taskId}/content` | 完成后返回鉴权 MP4，支持浏览器范围请求 |

任务响应包含 `id/prompt/model/seconds/size/status/progress/attempt/mediaAssetId/contentUrl/errorMessage`
以及创建、更新、完成时间；不返回 `providerJobId`、节点存储路径、API Key 或上游临时地址。

## 验收标准

- 未登录请求返回 401；不同工作区不能读取任务或视频。
- 缺少 Key、参数非法、上游拒绝和下载失败均显示真实原因并允许重试。
- 刷新页面不会丢任务或提示词草稿，后台同步不会覆盖尚未提交的输入。
- 完成任务的视频可在站内播放，响应不公开存储路径或 API Key。
- 两节点部署中 `/api/agent/videos/**` 始终带 `X-WAO-API-Route: linux-vpn-master-video`。
