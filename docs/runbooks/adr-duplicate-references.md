# 历史 ADR 重号的引用治理

审计日期：2026-09-19；基线：`a6b7d694c4db3ed06bf13f1be35d2a5953e4c905`。工程一致性检查识别了四组历史重号。不同文件记录不同决策，不能只凭编号推断含义，也不能按文件名排序决定哪一篇应当保留。

## 精确身份与引入来源

| 历史编号 | 精确主题与文件 | 引入提交 | 日期 |
| --- | --- | --- | --- |
| 023 | [AI Runner 主克隆游离 HEAD](../adr/023-ai-runner-detached-primary-clone.md) | `a5bb62a6` / PR #57 | 2026-07-26 |
| 023 | [飞书群聊历史补录与月归档](../adr/023-chat-sync-history-and-monthly-archive.md) | `6ef1ed3a` / PR #50 | 2026-07-26 |
| 152 | [空闲后批量推进最早待办](../adr/152-batch-oldest-task-idle-auto-run.md) | `59545305` / PR #433 | 2026-08-28 |
| 152 | [成功静默、失败主动通知](../adr/152-silent-success-delivery-notifications.md) | `f2ffd367` / PR #432 | 2026-08-28 |
| 172 | [可配置飞书任务表情工作流](../adr/172-configurable-feishu-task-reaction-workflow.md) | `87c4e609` / PR #479 | 2026-08-31 |
| 172 | [项目文件、图床与 HTML 资料分类](../adr/172-project-file-and-image-classification.md) | `e54ff5b4` / PR #486 | 2026-08-31 |
| 187 | [爬虫能力源码资产](../adr/187-crawler-capability-source-assets.md) | `ed96395b` / PR #514 | 2026-08-31 |
| 187 | [AI 有效交付与单位消耗](../adr/187-outcome-denominated-ai-efficiency.md) | `3b5bfe96` / PR #515 | 2026-08-31 |

引入提交由 `git log --follow --diff-filter=A` 核对。上述提交日期是本仓库记录，不以同日或 PR 号相近推断两个决策有从属关系。

## 本次已完成

对基线中 Git 跟踪文本搜索四组编号，结合段落主题核对引用归属，将 10 个文件中的 13 处裸编号改为带主题的精确文件链接；另补全两处原本已有精确链接的显示主题。没有改名、分配新编号、改变决策内容或修改 migration。

| 编号 | 原有裸引用 | 归属判定 |
| --- | ---: | --- |
| 023 | 2 处 | Runner 说明与 Qoder 引擎说明均讨论 worktree，指向主克隆游离 HEAD |
| 152 | 7 处 | 数据库 V110 条目指向批量待办；通知、交付及后续通知决策指向成功静默 |
| 172 | 2 处 | 三阶段表情决策中的两个引用均指向可配置表情工作流 |
| 187 | 2 处 | 资源验证与数据库编目说明均指向爬虫源码资产 |

ADR 自身标题、验收记录中的历史文件名仍保留原编号。后续引用这些文件时使用“编号 + 主题 + 完整文件路径”，避免重新引入歧义。工程检查仍会报告四组历史警告，不能把本次引用澄清说成重号已经消除。

## 阅读时还要核对后续决策

- [ADR-234](../adr/234-quiet-home-and-visible-client-updates.md) 明确取代成功静默 ADR 中 Jenkins 成功通知相关条款；引用历史文件不代表其每一条仍是当前规则。
- [ADR-176](../adr/176-feishu-three-stage-reaction-and-polling.md) 修正可配置表情 ADR 中的阶段合并和事件回调限定。
- [ADR-232](../adr/232-local-acceptance-and-user-production-verification.md) 定义本地验收后合并即完成 AI 交付的边界；历史 ADR 中的强制发布跟进要求应结合后续决策阅读。

本次只澄清文档引用，不据此修改通知、Runner、发布或业务行为。

## 如需消除历史重号

1. 再次刷新主分支，并用一致性工具核对当前占用编号；不要预留本文写作时的“下一个编号”。
2. 按完整文件身份制定旧路径到新路径的映射，记录取代或保留的理由；不能按编号批量替换。
3. 同步标题、正文、仓库内精确链接，并核对仓库外分享链接与历史任务引用。未找到的外部引用不能声称已覆盖。
4. 对比更名前后决策正文，确认没有夹带业务规则变化；验证链接、编号唯一性与暂存区差异后单独交付。

可复用查询：

```bash
git grep -n -i -E 'ADR[- _]*0*(23|152|172|187)([^0-9]|$)'
python3 deploy/scripts/check-engineering-consistency.py --base origin/main
```

查询只定位候选引用，不自动判断语义；本文不扫描数据库、任务正文、浏览器历史或外部系统。
