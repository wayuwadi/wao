---
name: pr-auto-merge
description: WAO 公开主仓库的 PR 验证和维护者合并约定。
---

# PR 验证与合并

遵循根 AGENTS.md 和 CONTRIBUTING.md，只维护 wayuwadi/wao。一项需求或 BUG 使用一个 PR，先在本地完成相称测试，检查文本、迁移编号、权限及保存行为冲突。

公开 CI 检查源码和敏感内容。合并依据实际检查结果与维护者授权，不能沿用旧私有仓库的临时自动授权。生产由 Windows Jenkins 从受信任 main 串行交付，具体约定见 docs/deployment.md。

不要从 Fork/PR 执行生产部署或读取私有配置；不将合并报告为已上线，不泄露凭据或用户数据。
