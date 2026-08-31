# 自媒体 Agent API 补充契约

## 内容素材预览

`GET /api/agent/publish/media/{mediaAssetId}/content`

- 身份：必须携带有效 `WAO_SESSION`；工作区由服务端根据当前账号推导。
- 成功：返回素材原始字节，`Content-Type` 使用落库媒体类型，`Content-Disposition` 为 `inline`，缓存仅允许私有缓存五分钟。
- 失败：素材不存在、不属于当前工作区、不在当前处理节点或原文件缺失时返回统一错误响应，不暴露存储路径。
- 路由：`/api/agent/publish/media` 前缀固定到 Linux 主节点，上传等非只读请求不得自动重放。

## 账号定位

`GET /api/agent/studio` 返回的 `profile` 与 `POST /api/agent/studio/profile` 请求/响应均包含：

- `niche`、`audience`、`tone`、`avoid`
- `contentTopics`、`lengthStructure`、`updateFrequency`、`primaryPlatforms`、`imageStyle`
- `configured` 仅为服务端响应字段；领域已填写时为 `true`

全部定位字段按当前工作区持久化，并作为真实选题推荐和内容生成的模型约束；没有配置模型时不伪造 AI 初始化结果。

`POST /api/agent/studio/profile/initialize` 接收最长 2000 字的 `needs`，仅在节点配置真实模型时生成并保存上述九项定位；模型不可用或返回不合法 JSON 时明确失败。

## 话题与人工发布状态

- `POST /api/agent/studio/topics`：请求包含 `topic` 与 `platform`，将成员手动灵感保存为当前工作区的 `NEW` 话题；不要求模型或热点来源。
- `POST /api/agent/publish/tasks/{taskId}/mark-published`：仅对没有官方发布能力、且处于等待确认或失败状态的内容开放。服务端记录 `PUBLISHED` 与真实确认时间；有官方发布能力的平台拒绝该操作。
