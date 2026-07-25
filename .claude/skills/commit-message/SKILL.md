---
name: commit-message
description: Use this skill whenever you are about to create a git commit in the WAO repository (wao-server, wao-web, wao-gateway, deploy, docs, or root). WAO's CI/CD pipeline forwards the full commit message to a Feishu (Lark) notification card the whole team reads, so every commit needs a real body, not just a one-line subject. Triggers on "commit", "git commit", "创建提交", "提交代码", "写提交信息", "commit message".
---

# WAO 提交信息规范：标题 + 正文

## 背景

`.github/workflows/ci-cd.yml` 里的 `notify` job 在每次 push 到 `main`（以及每个 PR）后，把这次提交的**完整提交信息**转发到团队飞书群的通知卡片「本次改动」区块（`change_summary` 取自 `head_commit.message`，PR 场景再拼上 PR 描述）。

这意味着提交信息不只是给 `git log` 看的——它会原样出现在同事的飞书通知里。只写一行标题的提交，卡片里就只有一句话，看不出改了什么、为什么改。

## 规则

创建提交时，commit message 必须包含：

1. **标题行**：Conventional Commits 格式，如 `feat(server): add vault encryption`，对齐 `CONTRIBUTING.md` / `AGENTS.md` 的提交规范。
2. **空行**。
3. **正文**：至少一段，说明这次改动**做了什么、为什么**——不是把 diff 复述一遍，而是给没看代码的人也能看懂的上下文。哪怕改动很小也要写一句人话，例如「修复知识库分组重命名后未持久化的问题」，不要留空。

飞书卡片对「本次改动」做了 1200 字符截断（见 `ci-cd.yml`），正文不需要极简到一句话，但也不必堆砌成整份变更日志——把最关键的信息放在前面几句。

## 何时可以只写标题

- 修 typo、格式化等确实没有实质内容可说的提交，标题本身已经说清楚时可以只写一行。这种情况应该很少见，拿不准就补一句正文。
- 这条规则只管「commit message 怎么写」，不改变「什么时候该创建提交」——仍然只在用户明确要求时才执行 `git commit`（沿用系统级 git 安全约定：不要未经确认主动提交）。

## 适用范围

这是仓库级约定，对所有向 WAO 提交代码的人都生效，不论是否使用 Claude Code——飞书通知由 CI 自动触发，和提交所用的工具无关。`CONTRIBUTING.md` 与 `AGENTS.md` 已同步这条规则，供不使用 Claude Code 的协作者查阅；这份 skill 文件随仓库一起被 git 追踪，任何人在这个项目目录下打开 Claude Code 都会自动看到它。
