# WAO 123 云盘异地备份

Linux2 每天 04:15 完成 Linux1 异机恢复快照后，05:00 将**同一份已验证快照**上传到 123 云盘，不再另做大型本地归档。快照包含 `wao`、`tools`、`wayuwa`、`tkwork` 四个数据库，以及两台机器的部署恢复材料；每周一次的快照还包含 Jenkins 恢复归档。个人知识库、小记、任务、图片和附件均存于 `wao` 数据库。

上传任务沿用异机快照的加密归档，只额外将其解密密钥用独立的云端恢复口令加密。Linux2 和 Linux1 原有快照保留策略不变；123 云盘保留 30 天。云端成功上传的标志是最后写入的 `manifest.json`，每个文件都有 SHA-256 校验值。

## 首次配置

在 123 云盘「工具中心 → 第三方挂载」中新建名为 `WAO Linux2 Backup` 的应用，只授权一个新的专用文件夹并授予读写权限。使用该应用生成的密码，不能使用云盘登录密码。WebDAV 地址以授权页面显示的值为准，通用地址为 `https://webdav.123pan.cn/webdav`。

在 Linux2 执行：

```bash
sudo /usr/local/lib/wao-backup/configure-offsite-backup.sh \
  'https://webdav.123pan.cn/webdav' '<授权页面显示的用户名>' 'WAO-backups'
```

脚本在终端隐藏读取应用密码和至少 20 字符的独立云端恢复口令，检查 WebDAV，再上传最近一份已验证的异机快照。口令以 `0600` 权限保存在 `/etc/wao-backup`。**云端恢复口令必须另存到不依赖 Linux1、Linux2 和 123 云盘的密码管理器**；若两台机器都丢失，仅有云盘账号仍无法恢复加密数据。

```bash
systemctl list-timers wao-offsite-backup.timer
systemctl status wao-offsite-backup.service
journalctl -u wao-offsite-backup.service --since today
```

上传位置为授权文件夹内的 `WAO-backups/linux2/<UTC 时间>/`。若当天异机备份失败，云端仍保留上一恢复点，须先修复异机备份；不能把旧快照冒充新恢复点。

## 恢复演练

恢复脚本只解密到指定的空目录，不停止服务或覆盖生产：

```bash
sudo /usr/local/lib/wao-backup/restore-peer-offsite.sh --list
sudo /usr/local/lib/wao-backup/restore-peer-offsite.sh \
  20260923T201855Z /var/tmp/wao-restore-drill
```

脚本先下载文件，核对云端清单和原始快照的 SHA-256，再依次解开云端密钥及恢复归档。`recovery/databases.sql.gz` 中含四个数据库，可在隔离 MySQL 中验证；`recovery/tables.txt` 是备份时的表清单。生产恢复需要另行确认目标和切换步骤。
