# 四库异机备份与指定位置恢复

> 历史操作手册：本文依赖 Linux1 peer、反向复制和旧恢复工具，已由 [Linux2 单节点异地加密备份](linux2-only-cloud-backup.md) 与 [ADR-291](../adr/291-linux2-only-deployment-and-wao-recovery.md) 取代。不要安装或启用本文中的 Linux1 步骤。

当前写入源是 Linux2 原生 MySQL 9.7，范围为 wao、tools、wayuwa、tkwork。Linux1 在线复制仍只含 wao。

## 安装与回退

先把现有 `/usr/local/lib/wao-backup`、对应 systemd 单元保存到 root 私有回退目录。
Linux2 安装 `create-peer-backup.sh`、`archive-peer-binlogs.sh`、`plan-peer-binlogs.py`、
`peer-retention.py`、`binlog-retention.py`；复用 `/etc/wao-backup/peer.env` 的现有口令及 SSH 信任。
Linux1 安装 `verify-peer-restore.sh`、`prepare-peer-pitr.py`、`check-peer-backup.py`，保持既有用户运行身份。
先手工完成一次全量、归档和隔离恢复，再启用 `wao-peer-binlog.timer`，保留每日全量 timer。
独立的 `wao-peer-backup-check.timer` 调整为五分钟；月度全量恢复保持原计划。

官方 `mysql:9.7.0` 最小镜像不含 mysqlbinlog。Linux1 需要先准备同版本恢复工具镜像：

1. 从 `https://repo.mysql.com/yum/mysql-9.7-community/el/9/x86_64/mysql-community-client-9.7.0-1.el9.x86_64.rpm` 下载官方客户端 RPM。
2. 在含 `cpio` 的 Linux1 执行 `bash build-mysql-recovery-tools.sh /私有目录/mysql-community-client-9.7.0-1.el9.x86_64.rpm`。
3. 脚本核对固定 SHA256 与 MySQL RPM 签名，只提取 mysqlbinlog 构建 `wao-mysql-recovery-tools:9.7.0`；不安装或替换宿主数据库。

恢复临时文件默认位于备份盘 `.restore-work`，拒绝 tmpfs，预留至少 20% 磁盘和解密空间。
外层服务限制 2 GiB，临时数据库另有 2 GiB 限制；Docker 容器资源不计入调用方 systemd 的内存上限。
异常强杀可能留下临时文件，只能在确认无演练运行后清理对应 `.restore-work/wao-peer-restore.*`，不可清理完整快照。

回退代码不会删除新格式备份。若停止增量归档，必须明确恢复点停止推进；不能保留“5分钟”承诺。
恢复脚本支持格式 1/2/3，不用旧脚本误读新格式。

## 恢复点

成功全量目录必须包含 COMPLETE、SHA256SUMS、recovery.tar.gpg；新格式还包含恢复坐标 recovery-point.json。
可信恢复以解密归档内受校验保护的坐标为准。binlogs/last-success.json 记录采集截止时间和传输完成时间，告警使用前者，避免慢传输掩盖陈旧数据。
每轮归档仍逐项核对已完成日志在异机的 `COMPLETE` 标记，但通过一次 SSH 会话批量核对；缺失任何标记时整轮失败，不更新 `last-success.json`。避免保留日志增多后反复建连拖慢五分钟任务。
没有最新全量及从其坐标开始的连续日志，不能声称可恢复到最新归档位置。

Linux1 隔离验收：

```bash
bash ~/.local/libexec/wao-backup/verify-peer-restore.sh \
  ~/backups/wao-peer/<完整快照UTC目录> \
  ~/.config/wao-backup/peer-passphrase \
  ~/backups/wao-peer/binlogs/last-success.json
```

第三个参数可以是经核对的指定位置 JSON，字段为 source_uuid、file、position。它必须落在已归档连续链中，且不能早于全量起点。
脚本只在临时容器恢复，结束即清理，不替换生产数据库。需要生产恢复覆盖时，先保留故障现场、核对目标与影响并取得该操作的明确授权。
回放全部批准范围的日志，不使用会遗漏跨库语句的 database 过滤来伪造恢复成功。

## 容量与保留

数据库每日全量至少七份，过七天且超出最少份数才清理；Jenkins 完整异机副本每周一次，本机日备不变。
最近一次 `jenkins=included` 的快照是 Jenkins 恢复来源，不要求每天的数据库归档都重复包含 Jenkins。
`latest-jenkins-snapshot` 保护最后一份成功的 Jenkins 快照，直到新副本完成，避免周备失败后被日常保留策略删除。
Jenkins 缺失会在元数据和运行日志明确记录，但不阻止四库备份发布。
binlog 只清理所有保留全量起点之前的归档，不修改 MySQL 原始日志过期配置。
空间不足时拒绝新任务并告警，保留既有恢复点，不进行无法恢复的自动删日志。

## 主库接管边界

本版四库全量依赖 Linux2 当前写入源，不能在 WAO 已切到 Linux1 后继续把旧 Linux2 WAO 数据当最新主库。
ADR-252 的接管流程保留 Linux2 采集，但要求 WAO 反向复制健康、核验新主 UUID 并等待新主 GTID 屏障；三个库仍是本机写主，WAO 明确标记为已核验副本。新 WAO 事务通过 log_replica_updates 进入本机归档，接管后重新制作全量并隔离验证。
不满足该拓扑时必须先确定各库来源、调整地址和凭据来源、建立新源全量起点、重新验证归档与复制方向。
source_uuid 不同的日志禁止拼接。只切应用连接而未更新数据保护流程不算接管完成。

## 实机验收（2026-09-21）

- `20260921T010340Z`：四库一致性导出、异机密文及坐标校验通过，包含 Jenkins，耗时 30 分 34 秒。
- `20260921T013502Z`：四库数据库日备通过，Jenkins 按周计划跳过，耗时 10 分 8 秒。
- Linux1 隔离恢复 222 张表，全部 CHECK TABLE 通过；从 mysql-bin.000020:590842963 回放到 mysql-bin.000021:206783，精确验证目标前标记存在、目标后标记不存在。演练耗时 3 分 23 秒，临时容器与明文清理；两条生产测试回执已定点清理。
- 曾因 `/tmp` 为 tmpfs 触发演练服务内存上限，已改磁盘临时目录；该失败不是 Linux2 宕机。
- 官方 9.7 客户端 RPM 的签名和 SHA256 校验通过，恢复工具镜像已构建。
- Linux2 正式五分钟归档首轮成功；Linux1 独立新鲜度检查通过。模拟缺备份告警获得通知服务成功回执。
- 13 项 Python 测试与全部 Bash 语法检查通过。生产主库接管未在本项执行。
