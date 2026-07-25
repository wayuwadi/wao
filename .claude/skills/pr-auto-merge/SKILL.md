---
name: pr-auto-merge
description: Deliver WAO changes during the temporary development phase. Prefer a locally validated direct main push; use a branch or PR only when isolation helps collaboration.
---

# WAO 开发期快速交付

## 适用阶段

仅适用于第一个正式版本上线前。每次执行前先检查 `AGENTS.md` 的“快速交付策略”是否仍存在；若已撤销，立即恢复受保护分支和 PR 评审流程。

## 默认流程

1. 开始变更前执行 `git switch main`、`git pull --ff-only origin main`，确认工作区没有他人的未提交修改。
2. 修改完成后执行与风险相称的本地测试。不要为了等待云端重复 CI 而停下。
3. 使用 Conventional Commit，正文必须包含 `Changes:`、`Why:`、`Validation:`，且只陈述真实执行过的验证。
4. 提交前再次获取 `origin/main`。主线未前进时直接 push `main`；主线已前进时先安全整合、重新跑相关验证再 push。
5. `main` push 自动触发唯一一次 CD、线上健康检查和飞书通知。持续跟踪到 deploy 与 webhook 完成，并验证受影响的线上入口。

## 何时使用分支或 PR

大型重构、多人同时修改相同区域、需要保留独立讨论或隔离试验时可以使用功能分支。此时 PR 不承担重复 CI 门禁：本地验证通过且无冲突即可自动合并，随后只由 `main` 触发部署。

## 不可放宽的边界

- 真实密码、Token、私钥、Webhook、App Secret 和私有 `.env` 永不写入 Git 或日志。
- 删除生产数据、不可逆迁移和明显扩大任务范围的操作仍需用户明确授权。
- 不修改已经执行的 Flyway migration。
