# Windows 草稿回归工具路径修复

- 基线：`d0a81a23`，Windows、Node 24.19.0；当前真实小记服务使用既有临时开发库，未连接原生产库。
- 复现：`cd wao-web && npm run test:acceptance-tools` 的既有 9 项中 5 项失败。3 项因 `path.relative` 返回 `..\\`，工具只识别 `../`，误拒绝系统临时目录；另 2 项因普通 Windows 用户无创建符号链接权限。
- 修复：按平台分隔符判断父目录；继续先解析真实路径，仓库内部路径和经目录链接回到仓库的路径仍拒绝。Windows 测试使用目录 junction、文件硬链接验证实际文件系统边界与原子替换，POSIX 保留原有符号链接测试。
- 验收：`node --test scripts/acceptance/faults.node.mjs scripts/acceptance/report.node.mjs`，9 项通过，无跳过；包含真实 HTTP 故障透传、报告恢复、完整性判断、目录链接防护和文件链接目标未被覆盖。
- 权限边界：Windows 不用 POSIX `0600` 推断 NTFS ACL；证据须存入受控本机私有目录。此修复不修改账号、数据库、业务保存逻辑或任何已执行 migration。
- 本机小记另外完成真实保存、失败恢复、两窗口竞争和窄屏验证，证据将随独立的小记回归资产交付；本 PR 不把它们声称为完整文档编辑回归。
- 交付类型：工程工具，无需运行制品部署；用户已确认沿用临时现状，Jenkins 和真实 AI Runner 阻塞单列，不手动触发构建。

运行：在 `wao-web` 执行上述 Node 测试命令。文档真实回归操作仍见[原手册](../runbooks/document-draft-regression.md)。
