---
name: pr-auto-merge
description: Deliver WAO changes during the temporary development phase. One requirement or one bug = one PR, created and merged without asking each time. Covers conflict checks against main (including semantic ones git won't flag), push, PR creation, and squash merge. Triggers on "推送分支", "创建 PR", "合并到 main", "交付", "发 PR", "push branch", "create PR", "merge to main".
---

# WAO 开发期交付：一个需求 / 一个 BUG 一个 PR

## 适用阶段

仅适用于第一个正式版本上线前。每次执行前先检查 `AGENTS.md` 的“快速交付策略”是否仍存在；若已撤销，立即恢复受保护分支和 PR 评审流程，不要再自动合并。

决策依据：`docs/adr/011-development-fast-delivery.md`（快速交付）与 `docs/adr/014-one-pr-per-change.md`（交付单元 = PR）。

## 核心规则

**一个需求或一个 BUG 就是一个 PR。** 多条需求不塞进同一个 PR；一条需求也不拆成多个 PR。用户已常设授权：**不必逐次询问**，完成本地验证后直接走完下面的流程。

**PR 不承担 CI 门禁。** `ci-cd.yml` 只在 `main` 的 push 上触发，所以 PR 页面上**没有 checks 是预期行为，不是失败**——不要 `--watch` 等一个永远不会出现的检查。合并前的门禁是：本地验证全绿 + `gh pr view` 的 `mergeable` 为 `MERGEABLE`。

## 前置条件

- `gh auth status` 显示已登录且 token 有 `repo` 权限。没有的话不要自动合并，改为给用户一个预填好的创建 PR 链接（`https://github.com/wayuwacn/wao/compare/main...<branch>?quick_pull=1&title=...&body=...`）。
- 提交信息已按 `.claude/skills/commit-message/` 的规范写好标题 + 正文，正文包含“改动”“原因”“验收”，且只陈述真实执行过的验证。

## 步骤

1. **起点对齐**：变更前 `git switch main`、`git pull --ff-only origin main`，再切功能分支（`feat/`、`fix/`、`docs/`、`refactor/`、`test/`、`chore/`）。

2. **跑完与改动相称的本地验证**：后端 `mvn test`（在仓库根目录跑，不要在 `wao-web/` 里跑）；前端 `npm run lint`、`npm run test:unit -- --run`、`npm run build`。

3. **核对与 main 的冲突**，在 push 之前做：

   ```bash
   git fetch origin
   git merge-tree "$(git merge-base origin/main HEAD)" origin/main HEAD | grep -c '<<<<<<<'
   ```

   非零说明有文本冲突，本地 `git merge origin/main` 解决后**重跑验证**。注意这个计数会被改动内容里字面出现的 `<<<<<<<` 干扰（比如这份文档自己），先用 `git rev-parse origin/main "$(git merge-base origin/main HEAD)"` 确认两者是否相等——相等就说明分支已经基于最新 `main`，没有可整合的东西，计数非零必是误报。

   **同时手工核对语义冲突**——git 不会报，但合并后会撞车：

   ```bash
   git ls-tree --name-only origin/main docs/adr/ | tail -5
   git ls-tree --name-only origin/main wao-server/src/main/resources/db/migration/ | tail -5
   ```

   两边各自新建了同编号的 ADR 或同版本号的 Flyway migration 时，文件名不同所以 git 静默通过，必须把本地的重新编号，并同步所有引用它的地方（`deploy/README.md`、`docs/api/*.yaml`、其他 ADR、提交正文）。

4. **push 分支**：`git push -u origin <branch>`

5. **创建 PR**：

   ```bash
   gh pr create --base main --head <branch> --title "<Conventional Commit 风格标题>" --body "<改动 / 原因 / 验收 / 需要注意>"
   ```

   标题和正文用中文——这段内容会被人在 GitHub 上直接看到。

6. **确认可合并后 Squash Merge**：

   ```bash
   gh pr view <PR号> --json mergeable,mergeStateStatus
   gh pr merge <PR号> --squash --delete-branch
   ```

7. **收尾**：`git fetch --prune origin`；`git switch main && git pull --ff-only origin main`。

8. **告诉用户**：合并到了哪个 commit、PR 链接是什么。不用额外发飞书消息——push 到 `main` 会自动触发唯一一次 CD、线上健康检查和飞书通知。

## 什么时候停下来问用户

- 本地验证有失败项，或存在尚未解决的冲突。
- `AGENTS.md` 的“快速交付策略”一节已被移除或改写为需要人工评审。
- 改动本身高风险：删除生产数据、不可逆迁移、修改鉴权 / 加密逻辑的核心假设、变更部署拓扑。常设授权是针对流程的，不是让人对危险改动放弃判断。
- 手上的工作实际横跨多个需求——先拆成多个 PR，而不是打包成一个。

## 不可放宽的边界

- 真实密码、Token、私钥、Webhook、App Secret 和私有 `.env` 永不写入 Git 或日志。
- 删除生产数据、不可逆迁移和明显扩大任务范围的操作仍需用户明确授权。
- 不修改已经执行的 Flyway migration。
