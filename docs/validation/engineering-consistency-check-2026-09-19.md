# 工程一致性检查验收（2026-09-19）

## 工具测试

`python3 -m unittest discover -s deploy/scripts -p 'test_engineering_consistency.py'`：11 项通过。

独立临时 Git 仓库验证了：正常基线、未暂存文件、前导零等价编号、历史 ADR 警告与新增重号错误、历史 migration 重号不豁免、migration 修改/删除/改名、并行分支同步后出现语义冲突、Markdown 相对路径/图片/引用式链接、代码示例忽略、坏链接与仓库外链接、外部符号链接、JSON 输出与三个退出码。

## WAO 真实工作区

基线 `645bca38bf3de504380aabfa30f0bf3c1e070025`。扫描 235 篇 ADR、153 个 migration 和 4 份新增/修改 Markdown，migration 无重号及相对基线的改写，本次文档路径引用无错误。

检测到基线已有四组 ADR 重号，作为历史警告保留，没有自动改名或改写引用：

| 编号 | 已有文件 |
| --- | --- |
| 023 | `023-ai-runner-detached-primary-clone.md`、`023-chat-sync-history-and-monthly-archive.md` |
| 152 | `152-batch-oldest-task-idle-auto-run.md`、`152-silent-success-delivery-notifications.md` |
| 172 | `172-configurable-feishu-task-reaction-workflow.md`、`172-project-file-and-image-classification.md` |
| 187 | `187-crawler-capability-source-assets.md`、`187-outcome-denominated-ai-efficiency.md` |

文件均位于 `docs/adr/`。最终结果应描述为“无新增错误，存在四组历史警告”，不能表述为仓库零问题。后续治理历史 ADR 时，需要同时核对正文编号和所有引用，独立交付。

## 边界

本次只增加本地辅助检查及开发说明，没有新增 CI 门禁、修改发布策略、连接业务数据库或更改现有 migration。Markdown 检查限于本次修改文件的常见路径引用，未进行全库或网络链接校验。
