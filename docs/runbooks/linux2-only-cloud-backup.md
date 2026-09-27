# Linux2 单节点异地加密备份

自 2026-09-27 起，WAO 的数据库写主在 Linux2。旧 Linux1 peer 备份和 MySQL 隧道已停用；保留其历史快照，不自动删除。本手册用于 Linux2 到云入口主机的独立恢复链，详见 ADR-291。

## 恢复点

- `wao-cloud-backup.timer` 每周日北京时间 04:30 生成 `wao`、`tools`、`wayuwa`、`tkwork` 的一致性逻辑快照。保留精确 MySQL binlog 文件和位置、Linux2 部署配置，并加密上传。只有云端 SHA-256 校验通过后，本地与云端恢复点才标记为完成。
- `wao-cloud-binlog.timer` 每 15 分钟归档已关闭的 binlog 到云端，检查源 UUID、文件连续性、长度与校验和。全量快照建立并验收后才能启用此 timer。
- 本地目录是 `/var/backups/wao-cloud/`，云端是 `/var/backups/wao-linux2-only/scheduled/`。两端文件均保留；脚本没有自动删除逻辑。云盘空间不足时任务会失败，必须先核定保留期限并明确批准历史恢复点的删除范围。
- `wao-cloud-backup-check.timer` 每小时核对本机快照、binlog 归档、云端完成标记和两端容量；持续故障沿用既有飞书备份告警通道，重复问题最多每四小时提醒一次。
- root-only `/etc/wao-backup/cloud.env` 保存路径与 SSH 身份位置，恢复口令在 `/etc/wao-backup/encryption-passphrase`，均不进入 Git、聊天或日志。独立的恢复口令副本应在离开 Linux2 的密码管理器中保管。

## 日常检查

```bash
systemctl list-timers wao-cloud-backup.timer wao-cloud-binlog.timer wao-cloud-backup-check.timer
systemctl show wao-cloud-backup.service wao-cloud-binlog.service wao-cloud-backup-check.service -p Result -p ExecMainStatus
sudo cat /var/backups/wao-cloud/last-success
sudo cat /var/backups/wao-cloud/binlogs/last-success.json
```

`last-success` 只是文件完成标记。还要核对云端同名目录的 `SHA256SUMS`，并定期在隔离 MySQL 中完成解密、导入和业务数量校验。不要在生产 MySQL 里试恢复。

## 隔离恢复

先将云端某一完成目录复制到隔离主机的空目录。使用独立保存的口令解密，校验压缩包，再导入隔离 MySQL：

```bash
sudo /usr/local/lib/wao-backup/verify-cloud-backup.sh \
  /var/backups/wao-cloud/20260927T142143Z /etc/wao-backup/encryption-passphrase
```

该脚本创建无网络、无宿主 MySQL 数据目录挂载的临时 MySQL，导入四库并核对每库表数，结束后删除临时容器。若在其他隔离主机检查从云端下载的副本，恢复口令应从密码管理器临时提供。也可只检查归档结构：

```bash
cd /empty/recovery-point
sha256sum -c SHA256SUMS
gpg --batch --pinentry-mode loopback --passphrase-file /secure/encryption-passphrase \
  --decrypt recovery.tar.gpg | tar -xf - -C /empty/restore
cd /empty/restore/recovery
sha256sum -c SHA256SUMS
gzip -t databases.sql.gz
```

恢复到快照之后的时间点时，按 `recovery-point.json` 中源 UUID、binlog 文件与位置，对照 `binlogs/` 中连续的已归档日志。先在隔离库验证目标 GTID 和业务数量；生产恢复仍按数据库专用操作单执行，不通过本手册自动覆盖线上库。

## 123 云盘可读内容

`wao-offsite-backup.timer` 是单独的可读内容导出，只包含正文及引用媒体，不能代替四库恢复点。2026-09-27 WebDAV 返回 401；修复授权后还需核对远端 manifest、内容数量和抽样下载校验，不能仅依据本机 `--inventory` 宣称云端同步成功。
