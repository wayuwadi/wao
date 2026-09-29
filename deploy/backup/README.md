# WAO 备份与可读内容副本

## 当前恢复路径

自 [ADR-291](../../docs/adr/291-linux2-only-deployment-and-wao-recovery.md) 生效后，Linux2 是唯一 Linux 部署节点。现行数据库、binlog、Nacos 和 WeKnora 异地加密恢复链见 [Linux2 单节点异地备份手册](../../docs/runbooks/linux2-only-cloud-backup.md)。

旧 Linux1 peer 全量、复制和恢复定时任务已退出当前部署流程。当前云端 binlog 链和 Nacos 快照仍使用受限 SSH 传往云备份目录，目标由私有 cloud.env 指定，并明确拒绝 Linux1 主机；这条链不依赖 Linux1。保留历史加密快照；停用旧任务不会删除快照、恢复口令或其他备份文件。

## 123 云盘可读内容副本

知识库文档、小记、任务和媒体的可读导出是方便浏览的内容副本，不含数据库结构、权限或历史版本，不能恢复 WAO 服务。此前记录的 WebDAV 鉴权未通过，因此不能据此宣称远端同步当前可用；本地只读 inventory 也不等于上传或恢复验收。

如需重新启用这条独立能力，应先核实授权、部署来源和远端回读校验，再单独更新同步配置与操作说明。所有凭据继续保存在受限的 Linux2 私有配置中，不进入仓库或日志。
