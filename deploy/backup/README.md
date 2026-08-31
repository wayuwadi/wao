# WAO 异地备份

Linux2 每天在 Jenkins 本地一致性备份完成后，生成一份可独立恢复的异地备份，并上传到 123 云盘。备份包含：

- WAO MySQL 全库逻辑导出（正文、图片、任务、AI Run 等同库数据都在其中）；
- Jenkins 最近 26 小时内的一致性本地归档；
- JumpServer 的 `config` 与 `data` 持久化目录；
- Linux2 生产目录下的私有环境、证书及其他部署恢复材料。

Redis 会话/缓存、Nacos 临时服务注册、Docker 镜像和可从 Git 重建的源码不重复备份。备份在 Linux2 本地只保留
7 天，在 123 云盘保留 30 天；这些默认值可用 service 环境覆盖。

## 首次配置

在 123 云盘网页或 App 的「工具中心 → 第三方挂载」中新建名为 `WAO Linux2 Backup` 的应用，只授权一个专用
空文件夹并授予读写权限。使用生成的应用密码，不能使用 123 云盘登录密码。官方通用 WebDAV 地址当前为
`https://webdav.123pan.cn/webdav`；以授权页面实际显示值为准。

部署完成后在 Linux2 上执行：

```bash
sudo /usr/local/lib/wao-backup/configure-offsite-backup.sh \
  'https://webdav.123pan.cn/webdav' '<123 云盘账号>' 'WAO-backups'
```

脚本交互读取专用 WebDAV 应用密码和至少 20 字符的独立加密口令，不把它们写入命令历史。配置以 `0600`
保存在 `/etc/wao-backup`，随后验证 WebDAV、启用每天 03:30（Asia/Shanghai）的 timer，并立即生成首份真实备份。
加密口令必须另存到不依赖 Linux1/Linux2 的密码管理器；123 云盘账号和服务器同时丢失时仍要能取回它。

常用检查：

```bash
systemctl list-timers wao-offsite-backup.timer
systemctl status wao-offsite-backup.service
journalctl -u wao-offsite-backup.service --since today
```

远端以 `WAO-backups/linux2/<UTC 时间>/` 分目录保存。`manifest.json` 最后上传，是一份备份完整提交的标记；
目录里只有加密分片和不含凭据的清单。

## 恢复演练

恢复脚本永远只解密到指定的空目录，不停止服务、不覆盖生产：

```bash
sudo /usr/local/lib/wao-backup/restore-offsite-backup.sh --list
sudo /usr/local/lib/wao-backup/restore-offsite-backup.sh \
  20260831T193000Z /var/tmp/wao-restore-drill
```

脚本会先下载清单和全部分片、逐片校验 SHA-256、验证 GnuPG 完整性与 tar/gzip 结构，再解包。演练人员随后按需：

1. 用 `recovery/database/*.sql.gz` 导入隔离的 MySQL 测试库并核对表数；
2. 展开 `recovery/jenkins/jenkins-home-*.tar.gz`，核对 Jenkins 配置与任务；
3. 核对 `opt/wao-jumpserver/{config,data}` 及 Linux2 生产 `deploy` 恢复材料。

演练完成后删除测试目录。生产恢复属于破坏性操作，必须另行确认精确目标，不能直接把演练目录覆盖到根文件系统。
