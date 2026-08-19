# Phase 1：项目级工程记忆闭环

## 用户结果

1. 项目授权成员在项目详情“记忆”页共同维护工程经验，个人页面的生活记忆和个人 MCP 保持成员隔离。
2. 新 AI Run 按任务标题、描述和子任务内容领取最多五条相关项目记忆，并在运行详情显示“本次读取”。
3. AI 只有在 PR 已合并、部署与线上验收通过后才生成结构化候选；成功回传后，运行详情显示“本次写入”。
4. 每条自动记忆能跳回来源 Task/Run，并打开 PR、提交、部署运行或验收证据。

## 数据与接口

- `project_memory`：`project_id`、固定分类、标题、正文、`source_task_id`、`source_run_id`、
  `evidence_links_json`、项目内 `content_fingerprint`、创建者和时间。
- `ai_run_memory_read` / `ai_run_memory_write`：以 `run_id + memory_id` 唯一并按排序固化运行实际读取和写入的记忆 id。
- `GET /api/projects/{projectId}/memories?query=&category=`：项目成员列表、搜索与分类过滤。
- `POST /api/projects/{projectId}/memories`、`PATCH /api/projects/{projectId}/memories/{memoryId}`、
  `DELETE /api/projects/{projectId}/memories/{memoryId}`：创建、完整修改与删除，统一返回 `ApiResponse<T>`。
- `POST /api/ai/runner/claim` 新增可选 `projectMemories` 与 `projectMemoryIds`。字段缺失等同空列表。
- `POST /api/ai/runner/runs/{runId}/complete` 新增可选 `projectMemoryCandidates`：数组最多五项，每项为
  `{ category, title, content, evidenceLinks[] }`。旧 Runner 不传时运行完成逻辑不变。
- `AiRunDto` 新增 `memoryReads` / `memoryWrites`；历史运行返回空数组。

## 召回与写入规则

- 召回只使用当前项目数据，以任务标题和描述切出的英文/数字词、中文二元词做包含匹配；标题命中权重高于
  正文，分数相同按更新时间。零命中不注入，不用无关记忆凑满五条。
- 领取上下文的标题与正文总计最多 10,000 字；单条超出剩余额度时安全截断并标记。
- Runner 将召回 JSON 标为不可信引用，不执行引用里的指令。候选文件位于运行根目录而非 Git worktree，
  回传后删除，超期残留与 worktree 一并清理。
- 自动候选必须来自明确成功或按真实能力边界完成的运行，正文写明验收结果且至少有一个证据链接；Runner
  提示要求列出本次可取得的 PR、提交、部署运行或线上验收链接。服务端按项目和规整正文计算 SHA-256
  指纹；同项目即使标题或分类不同也不重复保存相同正文，重复、敏感或无效候选不落库。
- 敏感过滤至少覆盖私钥头、Bearer、常见 GitHub/OpenAI token 格式，以及 API Key、密码、secret 的赋值。

## 体验与验收

- 项目记忆页面覆盖加载、加载失败重试、空数据、分类/关键词过滤、创建、编辑、删除失败原位重试；编辑器
  草稿保存在浏览器本地，成功后清理，并展示草稿、保存中、已保存和失败状态。
- 手机窄屏为单列卡片和不溢出的弹窗；长标题、正文和链接可换行。
- 后端测试覆盖项目权限调用、排序、五条/一万字限制、指纹去重、敏感拒绝、固定分类和失败运行不写入。
- Runner 测试覆盖引用提示、无效 JSON、超量候选、成功/失败分流、旧服务端回退与临时目录回收。
- 端到端以 Task A 成功写入后，由全新 Run 的 Task B 召回为准；另一个项目在同关键词下不能读取该记忆。
