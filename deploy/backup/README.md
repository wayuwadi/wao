# WAO 123 云盘可读内容副本

Linux2 每天北京时间 05:00 把 WAO 中**所有成员当前未删除的知识库文档、小记和任务**导出到授权文件夹内的 `WAO-可读文件/`。正文为 UTF-8 `.txt`，按知识库、项目和标题组织；这些文件可以直接作为普通文本阅读。文档和任务引用到的、数据库里仍存在的图片及附件按原格式放在 `图片/`、`附件/`。根目录的 `开始阅读.txt` 列出各类数量和失效媒体引用数。

导出连接当前配置的 WAO 写主：Linux1 写主时经 Linux2 的私有数据库通道读取 Linux1，Linux2 写主时读取本机。每次运行会核对写主角色、实例 UUID 和可写状态；源不符就失败，不得改读停止回放的副本来制造成功记录。

同步是单向的。脚本从 MySQL 只读导出，使用云端 `manifest.json` 的 SHA-256 比对：内容未变就不重传；新文件或改动文件在上传后校验远端大小，再抽样下载文本和媒体核对 SHA-256。需要覆盖的云端文件若已被手工改动，脚本会拒绝覆盖。标题变化或源记录删除后，只清理本脚本清单中的旧文件；若一次将删除超过旧清单的 20%，任务失败并保留云端文件。失效引用不妨碍其他内容导出，但计入清单。运行失败时不会把未完成同步写成成功清单。

新目录没有 30 天的定时清理规则；它保存当前内容的一份持续更新的副本。源内容被删除或改名时，旧文件会按上述规则从新目录清理。

这是一份**方便浏览的内容副本**，没有数据库结构、权限、历史版本或全部服务器配置，不能用于恢复 WAO 服务。Linux1/Linux2 间原有的加密数据库恢复快照和 binlog 备份继续运行。123 云盘旧的 `WAO-backups/` 加密快照目前保留，恢复工具和口令也保留；新定时任务不再每天上传整份加密快照，且不会删除旧加密快照。个人生活记忆、统一记忆等其他模块不属于本次可读导出范围。

## 配置与检查

123 云盘 WebDAV 授权仍只允许访问专用文件夹；应用密码放在 Linux2 root-only `/etc/wao-backup/webdav.env`。仓库不保存凭据。首次配置执行：

```bash
sudo /usr/local/lib/wao-backup/configure-offsite-backup.sh \
  'https://webdav.123pan.cn/webdav' '<WebDAV 用户名>' 'WAO-backups'
```

脚本隐藏读取 WebDAV 应用密码，验证授权后同步首次内容副本。已有 WebDAV 配置可直接运行：

```bash
sudo /usr/local/lib/wao-backup/run-readable-content.sh --inventory
sudo systemctl start wao-offsite-backup.service
systemctl show wao-offsite-backup.service -p Result -p ExecMainStatus
systemctl list-timers wao-offsite-backup.timer
```

`--inventory` 只读取数据库并输出数量，不上传。数据库连接来自现有 root-only 的 WAO 部署配置，不写入脚本或日志。
首次上传若中断且源内容随后发生变化，可人工使用 `run-readable-content.sh --resume-initial` 续传；该选项在云端清单建立后会被拒绝，日常定时任务不会使用。

## 旧加密恢复点

既有 `WAO-backups/linux2/<UTC 时间>/` 保持原样。若需要完整数据库恢复，继续使用：

```bash
sudo /usr/local/lib/wao-backup/restore-peer-offsite.sh --list
sudo /usr/local/lib/wao-backup/restore-peer-offsite.sh \
  20260923T201855Z /var/tmp/wao-restore-drill
```

恢复脚本只解密到指定空目录，不覆盖生产。云端恢复口令需保存在不依赖两台 Linux 主机的密码管理器。
