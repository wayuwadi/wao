# 天人配图与设置接口

所有 JSON 业务响应使用 `ApiResponse<T>`，要求已登录成员。GET 无生成副作用。错误：401 未登录、404 非本人或不存在、400 输入/状态无效、409 设置或解读版本冲突。

| 方法 / 路径 | 输入与输出 |
| --- | --- |
| GET `/api/tianren/settings` | `version` 数值、`autoImage`、两项提示词、`defaultInterpretationPrompt`、`defaultImagePrompt` |
| PUT `/api/tianren/settings` | 输入 `version,autoImage,interpretationPrompt,imagePrompt`；两项各 1–4000 字，原子 CAS；返回完整最新设置 |
| GET `/api/tianren/readings/{id}/visual` | 最新成功解读的 `interpretationRunId,state,title,change,keep,imageAttemptId,message,retryable`；没有视觉记录返回 `NOT_GENERATED` |
| POST `/api/tianren/readings/{id}/visual/image` | 输入 `interpretationRunId,requestId`；仅今日有效摘要，requestId 为 1–64 字母/数字/连字符；同解读重发或已有活动任务返回原状态 |
| GET `/api/tianren/readings/{id}/visual/images/{attempt}/raw` | 本人关联且成功的 PNG 二进制，no-store；无 JSON 包装，不开放匿名读取 |

状态为 `NOT_GENERATED/DISABLED/BRIEF_FAILED/QUEUED/RUNNING/SUCCEEDED/FAILED`。状态查询由当前运行持久状态计算，图片失败不改变解读成功状态。

同解读替换期间 `imageAttemptId` 继续指向旧成功图；新解读返回新绑定。`retryable` 表示摘要有效且无活动图任务，浏览器另依据今日/历史禁止历史生成。

图片上传、执行节点、claim、私有 prompt、关注点与对象存储键不通过这些浏览器响应暴露。终端/MCP 令牌没有因此增加算卦或配图权限。
