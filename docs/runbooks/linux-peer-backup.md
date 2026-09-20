# 两台 Linux 独立历史备份

123 WebDAV 尚未开通，按 2026-09-20 用户决定，当前先完成 Linux2 → Linux1 的恢复快照。
这份快照同时包含两台机器的配置；MySQL 复制不算备份，不从在线副本的数据目录复制文件。

## 范围与恢复点

- 主库 `wao` 的 InnoDB 一致性逻辑导出，包含正文、任务、图片和附件；包含存储过程、触发器和事件。
- Linux1 / Linux2 的部署目录，包括私有环境与证书；这些文件只存在私有加密归档中，不进入 Git。
- 最近 26 小时内通过 SHA-256 校验的 Jenkins 一致性归档。复用归档，不为此停止 Jenkins。
- 不包含其他应用数据库、JumpServer 全部数据、整个操作系统或 Docker 镜像；这些不计入本阶段恢复承诺。

每天 04:15（上海时间，随机延后不超过五分钟）生成，建议 RPO 为 24 小时；超过 30 小时没有完整备份时告警。
生成、加密、传输、远端 SHA-256 验证全部成功才发布时间戳目录与 `COMPLETE` 标记。写入中的目录以
`.incoming-` / `.staging-` 开头，不能当作可恢复快照。SSH 使用已有核验的主机密钥，禁止跳过验证。

本机与远端保留至少最近 7 份完整快照，并只删除早于 7 天的额外完整快照。失败不会删除旧恢复点。
只清理已完成且标记一致的时间戳目录，不删除未完成目录或符号链接。异常遗留需人工检查后处理。
每次开始前两端至少需要 12 GiB 可用磁盘；存储不足时失败并保留旧恢复点，不能用静默跳过冒充成功。

## 配置与安装

Linux2 root 私有配置 `/etc/wao-backup/peer.env`（0600）包含以下路径和连接参数，不提交真实配置：

```bash
PROJECT_ROOT=/absolute/production/wao
BACKUP_ROOT=/var/backups/wao-peer
PEER_HOST=backup-user@peer-host
PEER_ROOT=/home/backup-user/backups/wao-peer
PEER_DEPLOY_ROOT=/home/backup-user/deploy/wao/deploy
SSH_KEY=/etc/wao-backup/peer-ssh-key
KNOWN_HOSTS=/etc/wao-backup/peer-known-hosts
PASSPHRASE_FILE=/etc/wao-backup/peer-passphrase
```

仅配置两台受信任主机。加密口令文件使用随机值且 0600，分别保存在 Linux2 与 Linux1 的私有配置目录，
单台机器损坏时另一台仍可解密；两地同时丢失尚不能恢复。后续应将口令存入独立密码管理器并补上第三份存储。
目前复用现有部署 SSH 身份的独立 0600 副本；它不是只写备份的受限身份，因此不承诺抵抗双机同时失陷。

控制器安装与配置同步会安装创建、验证、保留与检查脚本及 systemd unit，但不会自动启用尚未验收的任务。
完成首份快照及恢复演练后执行：

```bash
sudo systemctl enable --now wao-peer-backup.timer
sudo systemctl status wao-peer-backup.service
sudo cat /var/backups/wao-peer/last-success
```

Linux1 用户安装 `check-peer-backup.{sh,py}` 到 `~/.local/libexec/wao-backup/`，将
`wao-peer-backup-check.{service,timer}` 安装到 `~/.config/systemd/user/`。确保该用户已启用 linger。
该检查独立于 Linux2 和 WAO 应用运行，从现有私有环境读取已配置的飞书机器人地址，30 分钟检查一次；
同一异常最多每 4 小时发送一次。通知失败不记录已送达，不输出地址或凭据。正常状态静默。

```bash
systemctl --user daemon-reload
~/.local/libexec/wao-backup/check-peer-backup.sh --check-only
systemctl --user enable --now wao-peer-backup-check.timer
```

同时安装 `restore-latest-peer-backup.sh`、`verify-peer-restore.sh` 到同一用户脚本目录，安装并启用
`wao-peer-restore.{service,timer}` 到用户 systemd。每月第一个周日 05:30 在 Linux1 自动恢复最新完整快照；
失败标记由独立检查任务告警，成功后清除。一次恢复成功不代表后续每一份备份都已实际恢复。

## 隔离恢复演练

```bash
bash verify-peer-restore.sh /absolute/snapshot /absolute/private/passphrase-file
```

脚本先校验密文和归档内所有文件，再启动 `mysql:9.7.0` 临时容器：无网络、无发布端口、独立数据卷、
1 CPU / 2 GiB 内存、关闭事件调度与 binlog；客户端和服务端最大包均为 1 GiB，支持数据库内的大附件。
导入 SQL 后核对完整表名清单、逐表执行 `CHECK TABLE` 并要求全部 OK（MySQL 9 不依赖 mysqlcheck），并检查配置
和 Jenkins 归档可读。随机口令不回显；正常结束和失败均清理该临时容器、临时卷及解密目录。
不得把该脚本改为连接线上数据库，也不能用生产恢复或切主替代隔离演练。

导出期间另一个连接持有 `LOCK INSTANCE FOR BACKUP`，避免 DDL 与快照重叠，普通事务写入继续执行。
取得锁最多等待 30 秒，失败就终止本次备份；导出后立即释放，不在加密与跨机传输期间持有。
有大规模 schema 发布时仍应避开每日备份时刻，避免发布迁移等待这段锁。

## 回退与故障定位

停用新增任务不会停止 WAO：Linux2 `systemctl disable --now wao-peer-backup.timer`，Linux1 用户停用
`wao-peer-backup-check.timer`。保留历史快照和口令，禁止回退时自动删除。
检查 `journalctl -u wao-peer-backup.service` 和用户侧检查服务日志；不要输出私有配置全文。
还原业务主库需要单独确认目标、隔离旧写入口、备份当前数据；本任务不自动执行生产数据覆盖。

## 2026-09-20 首轮实测

- 快照 `20260920T065847Z`，加密文件 6,071,156,548 字节；Linux2 服务成功退出，生成和直连传输共 24 分 12 秒。
- 两机 SSH 连接使用各自家庭 IPv6 地址，没有借道云机；该时长不能当作千兆上行性能保证。
- Linux1 隔离恢复在 15:30 成功结束，源库 158 张表的完整名称清单匹配，SQL 导入和逐表检查通过，临时容器清理完成。
- 本次隔离演练耗时约 1 分 52 秒，包含解密与归档检查；这不是生产完整恢复或切主 RTO。
- 大附件导入需要服务端与客户端均设置 1 GiB 包限制；该调整仅用于临时恢复库。
- 备份锁片段已在真实主库验证可获得和释放，并可在持锁期间执行一致性 schema 导出。
- 新鲜度与磁盘检查正常，未向真实通知群发送模拟故障；故障判断由本地自动测试覆盖。
