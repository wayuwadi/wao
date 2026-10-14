---
name: wao-dev-batch-delivery
description: WAO 公开主仓库的本地验证、PR 和维护者 Windows Jenkins 发布约定。
---

# WAO 验证和交付

遵循根 AGENTS.md、CONTRIBUTING.md 和 docs/deployment.md。只维护 wayuwadi/wao；新需求先本地验证，再提交独立 PR。

生产发布由维护者 Jenkins 串行构建受信任 main，复用已有协调器；旧 Linux 流程不再适用。检查私有兼容迁移、备份、制品哈希和实际版本；不重置数据库，不将合并当作上线。公开仓库与此 skill 不授予额外生产操作权限。
