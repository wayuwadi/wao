# 浏览器业务 AI 本机执行

依据 ADR-313，补充既有规格的执行路由；旧规格中的 Runner 路由不再适用。用户输入、候选确认和持久化协议保持原有约束。

## 现用调用清单

| 功能 | 公共入口 | 当前执行方式 |
| --- | --- | --- |
| 文档选区润色、润色工作台 | `TextPolishService` / `TextPolishJobService` | `POLISH` 用途的本机持久队列 |
| 页面问答、助手工作台、带图问答 | `AiAssistantService` | 本机队列，私有会话事务回写 |
| 聊天复盘、自动复盘 | `ChatReviewAiQueue` | 本机只读任务，租约与运行关联保持原协议 |
| 小记 AI 挖掘 | `NoteMiningService` | 本机只读任务，结果继续为待确认候选 |
| 人生复盘 | `LifeReviewService` | 本机只读分析，沿用已有复盘保存 |
| 首页每日建议 | `DailyGuidanceService` | 本机只读分析，沿用缓存与持久化 |
| 历史系统改进建议服务 | `AiEvolutionService` | 共用只读路由已兼容本机；原 AI Run 页面与自动代码执行保持退役 |
| 小记标题、正文、标签与文章配图 | `LocalNoteAiService` | ADR-306 已实现，此次共用执行器 |
| 天人一卦解读和追问 | 专用本机入队入口 | ADR-308 已实现，保持专用提示词与状态协议 |

## 状态与选择

- `/api/assistant/status` 的 `local-codex` 使用执行器启用、目录可用和真实模型目录验证，不查询旧 Runner。只表示可接受请求，不保证上游登录、网络或额度一直可用；执行失败仍反馈真实错误。
- `/api/assistant/capabilities` 在原有获准 `SERVER_API` 前提供 `{ id: "local-codex", provider: "codex", executionMode: "LOCAL_CODEX", model: "gpt-6-astra", available, message }`；不暴露凭据和实例内部能力 ID，不列出 `RUNNER`。
- 启用本机后统一默认走本机。旧复盘集中 Codex ID 仅通过脱敏能力元数据兼容；已经删除或停用的账号需重新选择。显式 API 选择仍严格校验用途许可；不因本机失败切换供应商。
- 本机队列内部继续使用专用目录稳定生成的 `local-notes-*` 实例 ID 和 `secure_queued → provider_running → succeeded/failed`；API 队列保持 `provider_queued`，两种 worker 不互领。

## 输入与结果保护

用户资料、页面摘要、选区和图片均为不可信引用，不能改变系统约束。无 shell、外部 MCP、子代理、文件访问或联网；图片作为当前请求的显式输入，最多四张，逐张校验成员归属，再暂存数据库图片字节，不接受任意模型路径。环境变量沿用白名单，不向子进程传递数据库或 API 密钥。既有文章配图可单独开启图片生成工具。

润色原文始终可见；失败、等待、关闭或重新润色不修改正文，只有用户确认才替换冻结选区。原有文档版本冲突、草稿防丢和重试有效。问答回复与持久运行的完成使用 CAS 和同一事务，不让过期回调覆盖终态或重复追加回复。只读后台任务不追加问答历史。

## 已退役依赖

`AiRunService` 的项目派工、`AiTaskAutoRunService`、`HttpPerformanceAiRunJobHandler`、飞书反应派工与系统改进自动代码执行仍关闭；旧 Runner claim / complete / 原图回传和账号配置只是历史兼容代码，公开执行接口保持 410。历史记录、日志、图床和已执行 migration 不删除。本次不恢复自主改代码、公开内容发布或后台通知。

## 验收

自动回归覆盖公共本机入队、`POLISH` 用途、实例隔离、无 Runner / 凭据依赖、API 明确选择、本机故障不回退、忙碌冲突、私有会话和图片成员鉴权；本机 CLI 输入校验覆盖数量、类型、空数据、大小和工具禁用。

真实验收依 [本机执行运行手册](../runbooks/local-note-ai.md) 使用专用端口、状态目录和限定文档范围，连接既有数据库：润色工作台等待后得到真实候选，确认前原文不变，刷新可回看；助手选择本机后问答返回并进入本人历史。额外背景分析只用登记的合成输入。报告明确记录真实通过项与未验项，不能以 mock 或接口成功替代页面链路。
