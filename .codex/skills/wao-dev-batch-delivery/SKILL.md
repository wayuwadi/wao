---
name: wao-dev-batch-delivery
description: WAO 本地开发与验收后提交、推送并合并 PR，合并后即交付，线上验收由用户负责。仅在用户另行要求时跟进 Jenkins 发布；不授权其他项目自动合并。
---

# WAO 本地验收与交付

按 ADR-232，默认完成条件为本地验收通过和 PR 已合并。线上验收由用户负责；不沿用 ADR-202 强制等待发布和二次验收的旧结束条件。

## 默认开发流程

1. 读取项目 AGENTS、相关规格和 ADR，核对已有改动归属，基于最新 main 开发；并行工作使用独立工作区。
2. 在本地运行受影响功能，直接复用既有线上数据库连接，完成相称测试与真实用户链路验收，不自建数据库。编译、接口 200 或页面可打开不能替代体验验收。
3. 连接真实数据不扩大操作范围；保护凭据和业务数据，不将清库或重置测试指向线上，不修改已执行 migration。破坏性生产操作仍需明确授权。
4. 一个需求或 BUG 一个 PR。本地验证通过后创建详细中文提交、推送功能分支、建 PR 并 Squash Merge 到 main；保留现有自动执行授权。合并前检查普通冲突及 ADR / Flyway 编号冲突。PR 没有 Jenkins checks 是预期行为。
   - 刷新并同步 `origin/main` 后，可运行 `python3 deploy/scripts/check-engineering-consistency.py --base origin/main` 辅助核对编号、migration 不可变性和本次文档路径；退出码 1/2 需先解决，历史 ADR 警告如实保留，不自动改写历史。它不替代暂存区审阅或本地功能验收。
   - 每个 main 发布批次自动生成 Git 提交档案和当前版本说明，核对制品 SHA、首条提交及线上清单；不依赖人工精选。文档/测试也进入提交记录，只有这些变更时只更新网页静态制品、不重启后端，不再笼统说“无需部署”。产品精选仍可在同一 PR 更新 `currentRelease.json` 和 `releaseHistory.json`，新批次置前、旧记录保留。已有更新检测与草稿保存屏障不绕过。
   - 用户可感知变化在同一 PR 维护 `currentRelease.json` 的用途与 `steps`，并将完整对象放到 `releaseHistory.json` 最前；`sourceSubject` 精确对应将要 Squash Merge 的中文提交标题（可包含 Conventional Commit 前缀，不含 PR 编号）。简单修复不强制配图，复杂功能可附带专用 `demo` 或制品内截图。构建只在标题匹配时采用这些说明，不匹配时自动记录仍更新并如实提示说明缺项，禁止复用旧介绍。格式与图片路径见 `docs/specs/update-unboxing-2026-10-04.md`。
5. 合并后交付改动、本地验证结果、PR 链接和合并 SHA。默认不等待 Jenkins、不轮询发布、不创建回访、不做线上二次验收；Jenkins 自身的自动部署、健康检查与通知保持运行。不要把合并说成上线。

本地连接或验收受阻时明确报告未验证项，不伪报通过，不自动改为发布后补验。遵循当前用户对具体任务的进一步指示。

提交使用中文 Conventional Commit，功能 scope 使用稳定功能 id；正文包含“改动：”“原因：”“验收：”。不提交凭据。

## 另行请求的发布跟进

只有用户明确要求跟进发布、监控或线上验收时，读取 [release-coordination.md](references/release-coordination.md)，复用已有 Jenkins 批次和唯一协调者；不因默认开发任务自动进入此模式。
