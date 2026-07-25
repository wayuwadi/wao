---
name: pr-auto-merge
description: Use this skill after finishing and committing a piece of work on a feature branch in the WAO repository, when you need to get it into main. Covers checking for merge conflicts against main, pushing, creating the PR, waiting for CI, and squash-merging — the current (dev-phase, temporary) WAO merge policy. Triggers on "推送分支", "创建 PR", "合并到 main", "push branch", "create PR", "merge to main".
---

# WAO 分支 → PR → 合并 main 流程（开发测试阶段）

## 背景

WAO 现在处于开发测试阶段，管理员（@wayuwadi，仓库 admin）已授权：CI 全绿后可以直接 Squash Merge 到 `main`，不必等待人工 Review（详见 `AGENTS.md`「合并策略（开发测试阶段，临时）」）。这条授权是阶段性的，临近正式上线会被收回——执行前留意 `AGENTS.md` 里那一节是否还在，如果已经被删除或改写为「需要人工 Review」，说明策略已经变了，改走人工评审流程，不要再自动合并。

飞书通知已经调整为只在 push 到 `main`（也就是合并发生）时触发一次，不会在开发分支的每次提交上刷屏，所以完成下面的合并后不需要另外手动发通知——CI 自己会发。

## 前置条件

- `gh auth status` 必须显示已登录、且 token 有 `repo` 权限。没有的话不要尝试自动合并，按第一次遇到时的方式提示用户跑 `! gh auth login`，改为给用户一个预填好的创建 PR 链接（`https://github.com/wayuwacn/wao/compare/main...<branch>?quick_pull=1&title=...&body=...`）。
- 提交信息已经按 `.claude/skills/commit-message/` 的规范写好标题 + 正文。

## 步骤

1. **核对 main 冲突**，在 push 之前做：
   ```bash
   git fetch origin
   git merge-tree "$(git merge-base origin/main HEAD)" origin/main HEAD | grep -c '<<<<<<<'
   ```
   非零说明有真实冲突，在本地 `git merge origin/main` 解决后再继续（解决方式和上一次一致：文本冲突照常 resolve；同时检查有没有「文件名不同但语义冲突」的情况，比如两边各自新建了同一编号的 ADR、同一编号的 Flyway migration——这种 git 不会报冲突，但语义上撞车，需要手动重新编号）。

2. **push 分支**：
   ```bash
   git push -u origin <branch>
   ```

3. **创建 PR**：
   ```bash
   gh pr create --base main --head <branch> --title "<Conventional Commit 风格标题>" --body "<Summary + Test plan>"
   ```
   标题和正文对齐 commit message 的规范——这段内容也会被人在 GitHub 上看到。

4. **等 CI 通过**：
   ```bash
   gh pr checks <PR号> --watch
   ```
   `backend`、`frontend`、`containers` 三项必须全部 `pass`。`deploy` 在 PR 阶段本来就会 skip，不用管。任何一项失败：**停下来，修复问题，重新推送**，不允许绕过或强制合并。

5. **CI 全绿后合并**：
   ```bash
   gh pr merge <PR号> --squash --delete-branch
   ```
   用 Squash Merge（对齐 `CONTRIBUTING.md` 的既有约定），并删除源分支。

6. **收尾**：`git fetch --prune origin` 清理本地过期的远程分支引用；如果当前工作目录还停在已删除的分支上，`git checkout main` 切回去。

7. **告诉用户**：合并到了哪个 commit、PR 链接是什么。不用额外发飞书消息——push 到 `main` 会自动触发一次，用户会在群里看到。

## 什么时候不要自动合并

- CI 有任意一项失败或还在跑。
- 本地发现和 `main` 有冲突且还没解决。
- `AGENTS.md` 里的「合并策略（开发测试阶段，临时）」那一节已经被移除——说明项目进入了需要人工评审的阶段。
- 这次改动明显是高风险操作（删除生产数据、修改鉴权/加密逻辑的核心假设、变更部署拓扑等）——按系统级安全准则，这类改动即使 CI 通过也应该先跟用户确认一句，而不是闷头合并。
