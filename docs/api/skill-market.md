# Skill 市场 API 契约

## 公共接口

- `GET /api/public/skill-categories`：返回固定分类。
- `GET /api/public/skills`：支持 `keyword`、`category`、`platform`、`sort=newest|downloads|favorites`、`page`、`pageSize`。
- `GET /api/public/skills/{handle}/{slug}`：返回当前公开版本、历史公开版本和登录成员的收藏状态。
- `GET /api/public/skills/{handle}/{slug}/files?version=...&path=...`：只读预览已审核版本中的单个 UTF-8 文本文件；二进制、非 UTF-8 或超过 512 KiB 的文件返回不可预览原因。
- `GET /api/public/skills/{handle}/{slug}/download?version=...`：下载已审核 ZIP，响应头 `X-Skill-Sha256` 返回包哈希。

## 成员接口

- `GET|PUT /api/skills/publisher/me`：查看或创建独立公开身份。`handle` 创建后不可变。
- `POST /api/skills`：`multipart/form-data`，`metadata` 为 JSON，文件为单个 `archive` 或对齐的 `files` + `paths`。
- `GET /api/skills/mine`：返回当前作者的 Skill 与全部版本。
- `PATCH /api/skills/{skillId}/versions/{versionId}`：自动保存未提交草稿元数据。
- `POST /api/skills/{skillId}/versions/{versionId}/submit`：提交人工审核。
- `DELETE /api/skills/{skillId}/versions/{versionId}`：仅删除未提交草稿。
- `PUT /api/skills/{skillId}/listed?value=...`：上下架当前已审核版本。
- `PUT /api/skills/{skillId}/favorite?value=...`：收藏或取消收藏。

## 管理员接口

- `GET /api/admin/skill-reviews`：待审队列，包含纯文本 `SKILL.md`、文件树和扫描结果。
- `POST /api/admin/skill-reviews/{versionId}/approve`：通过并原子切换当前公开版本。
- `POST /api/admin/skill-reviews/{versionId}/reject`：驳回，`note` 必填。

所有 JSON 接口使用 `ApiResponse<T>`。公共下载、文件预览与成员上传会经过 Redis 限速；Redis 短时故障时业务不被中断。
