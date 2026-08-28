---
name: commit-message
description: Use this skill whenever you are about to create a git commit in the WAO repository (wao-server, wao-web, wao-gateway, deploy, docs, or root). WAO's Jenkins notification uses commit subjects as a concise Feishu change list, while the full body remains the linked delivery evidence. Triggers on "commit", "git commit", "创建提交", "提交代码", "写提交信息", "commit message".
---

# WAO 提交信息规范：标题 + 正文

## 背景

`Jenkinsfile` 在 `main` 的批量发布结束后，把批次内每条提交的**标题**整理成团队飞书群的「本次改动」清单；完整提交正文通过卡片里的提交和 Jenkins 链接查阅。

这意味着标题必须言简意赅地说清用户能观察到的结果，正文则承担改动范围、原因和验收证据。短卡片不再复制正文，但 Git 历史仍必须能独立回答为什么改、如何验收。

## 规则

创建提交时，commit message 必须包含：

1. **标题行**：保留 Conventional Commits 的 `type(scope):` 前缀，冒号后的描述必须使用中文，例如 `feat(server): 增加密钥库加密`。
2. **空行**。
3. **正文**：固定包含“改动：”“原因：”“验收：”三个字段，字段内容使用中文。代码标识、API 路径、命令和专有名词可保留原文。

飞书卡片最多展示六条提交标题；更多改动折叠为数量并引导查看 Jenkins。正文不必堆砌成整份变更日志，但必须保留具体事实和验收命令。

即使只是修正错别字或格式，也要保留三个正文字段；未执行测试时在“验收”中写明原因，不得虚构结果。

## 适用范围

这是仓库级约定，对所有向 WAO 提交代码的人都生效，不论是否使用 Claude Code——飞书通知由 CI 自动触发，和提交所用的工具无关。`CONTRIBUTING.md` 与 `AGENTS.md` 已同步这条规则，供不使用 Claude Code 的协作者查阅；这份 skill 文件随仓库一起被 git 追踪，任何人在这个项目目录下打开 Claude Code 都会自动看到它。
