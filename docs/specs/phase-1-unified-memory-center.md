# Phase 1：WAO 统一记忆中心

## 用户结果

1. 成员在一处维护个人、组织、项目、机器与应用长期记忆，并在多台电脑上读取同一份已发布内容。
2. 个人明确保存后立即生效；组织和项目候选经过负责人审核才进入默认 AI 上下文。
3. 每次修改生成不可变版本，可查看来源、发布人、历史并通过新版本回滚。
4. Codex 使用设备令牌和上下文 URL，只能访问该设备被授权的范围；不依赖本机记忆文件同步。

## 数据与生命周期

- `memory_document` 保存稳定身份、范围、分类、生命周期和当前发布版本指针；`memory_revision` 保存不可变
  草稿、已发布或已拒绝版本及内容指纹、基础版本、作者、变更摘要和可选失效时间。
- 固定分类：个人 `preference/responsibility/plan/decision/context/experience`；组织
  `decision/policy/standard/glossary/responsibility/context`；项目
  `decision/convention/pitfall/verification/operation/context`；机器和应用复用工程类分类，范围 id 分别来自
  已配对 Runner 与其真实上报的 AI 引擎，因此后续新应用无需新增数据表。
- `memory_collection_document` 把一台 Runner 的机器/应用报告映射到稳定文档；原始 Codex 本机文件不上传，
  正文无变化时不生成重复版本。
- `memory_source` 保存来源引用；`memory_attachment` 绑定版本；`memory_access_grant` 保存设备范围能力；
  `memory_outbox` 驱动派生任务；`memory_usage_log` 只记录元数据和耗时。
- 修订必须携带 `baseRevisionId`。基础版本落后返回 409，服务端不丢弃客户端草稿。废止和失效内容默认
  不进入搜索，但仍可在明确状态筛选中查看并生成新版本。

## API 与 MCP

- `GET/POST /api/memories`：按范围、分类、状态、关键词查询，或创建个人正式记忆/共享候选。
- `GET /api/memories/{id}`、`GET /api/memories/{id}/revisions`：读取详情与版本历史。
- `POST /api/memories/{id}/revisions`：基于当前版本修订。
- `POST /api/memory-revisions/{id}/publish|reject`、`POST /api/memories/{id}/archive`：审核和生命周期。
- 兼容 MCP `/api/mcp` 保留；上下文 MCP 为 `/api/mcp/memory/personal`、
  `/api/mcp/memory/organization/{orgId}`、`/api/mcp/memory/project/{projectId}`。
- 公共工具：`search_memory`、`get_memory`、`memory_source_options`；个人另有 `save_memory`，共享域另有
  `propose_memory`。项目 Runner 使用 Run 的项目身份，不继承执行电脑的个人 MCP 权限。

## 体验、迁移与验收

- “记忆中心”顶层切换五类范围，提供分类/状态/关键词过滤、版本时间线、候选审核、废止和项目深链。
  本地草稿覆盖保存中、已保存、冲突保留和失败重试，窄屏保持可操作。
- Runner 每日空闲时把 `CODEX_HOME/memories/` 的有界脱敏文本交给禁用外部工具的 Codex 生成机器/应用
  报告；报告只形成候选。无目录、无合格内容或生成失败时展示最近错误并延迟重试，不创建演示记忆。
- V89 幂等地把个人、项目和历史共享记忆迁成已发布 v1，保留稳定 id、来源、附件、时间与正文指纹。
  过渡期旧入口继续工作并影子写统一模型；旧表不在本阶段删除。
- 验收覆盖：跨成员/组织/项目返回 404；吊销令牌立即失效；候选发布前不可召回；两设备并发后提交者
  得到 409；敏感凭据候选被拒；迁移记录/指纹/附件字节/来源/时间一致；重复 outbox 不重复发布。
- 每个范围准备 10,000 条数据验证关键词搜索 P95 不超过 500ms，固定问答集召回不低于 80%；个人、组织、
  项目各执行一次“创建/候选→发布→另一设备检索→修订→回滚”真实链路。
