# WAO MySQL 受控接管操作单

2026-09-21 接管后状态：Linux1 为 WAO 写主；Linux2 原生实例继续写其他三个库，并以 wao-return 接收 WAO 副本，旧 wao_app 保持锁定。下文提升步骤仅适用于原始“Linux2 写主、Linux1 只读副本”状态，不能在当前角色上重复执行。脚本不是自动选主器，不能因为检测到 Linux2 不通就执行提升。

## 操作前必须具备

- 已批准明确窗口，预计 WAO 登录、任务保存和 Agent 有数分钟维护中断；禁止并发发布与 root 等特权身份写 WAO。
- 独立恢复 SSH 可用、最新四库备份/连续日志已异机验证、隔离恢复通过；现场记录当前两机 SHA、GTID 和路由配置。
- Linux1 应用与复制身份已准备且只读登录验证通过。已有账号不得重置密码；准备工具是一次性缺项修复。
- 当前部署已包含 ADR-250 参数、ADR-252 角色校验和 bootstrap 拒绝提升节点重建的保护。
- 比较 GTID 来源历史，不能只看延迟为零和当前表一致。副本本地初始化/历史恢复产生的 GTID 必须先核清；存在未被旧主覆盖的本地事务时，禁止提升后直接 AUTO_POSITION 回接。脚本会在修改副本通道前拒绝此情况，回接也要求旧主覆盖提升回执的 baseline_gtid；旧版缺少此字段的回执不能回接。
- 持续持有 Linux2 `/run/lock/wao-infra-maintenance.lock` 排他锁，直到全部应用和反向复制验收结束。切换前先等已有发布释放共享锁。

## 预先安装（不提升角色）

复制 `mysql-guarded-recovery.py` 到两节点私有运维目录。Linux2 更新独立备份目录中的 create-peer-backup.sh、archive-peer-binlogs.sh、verify-backup-source.py，默认角色下手工验证来源检查。

Linux2 原生 mysqld 实际通过 `/etc/my.cnf` 启动，二进制是 `/www/server/mysql/bin/mysqld`。备份该配置后，把仓库 `deploy/mysql/wao-return.cnf` 安装到 `/etc/wao-mysql-recovery/return.cnf`，在主配置追加一行：

```ini
!include /etc/wao-mysql-recovery/return.cnf
```

执行 `/www/server/mysql/bin/mysqld --defaults-file=/etc/my.cnf --validate-config`，必须成功。此时不重启原生 MySQL；动态通道过滤在接管步骤配置，文件用于后续重启。不得把其他库一起设为只读。

## 明确批准后执行

1. 获得维护锁，暂停入口写入并确认没有并发特权操作。记录应用当前镜像 SHA；保留两台部署私有环境原文件，权限保持不变。
2. Linux2 root 本地从现有 root-owned 配置加载 `DB_ROOT_PASS`、`MYSQL_REPLICATION_PASSWORD` 等到进程环境，不打印、不复制凭据到聊天或 Git。执行：

   `python3 mysql-guarded-recovery.py fence --state /var/lib/wao-mysql-recovery --maintenance-confirmed`

   工具检查授权范围、锁定 wao_app、清除旧连接后输出围栏回执路径。其他三个库不停止。
3. 将该非凭据回执通过既有严格 SSH 通道送到 Linux1 私有目录。两分钟内执行：

   `python3 mysql-guarded-recovery.py promote --state ~/.local/state/wao-mysql-recovery --evidence <围栏回执> --maintenance-confirmed`

   若超时或检查失败，保持旧主围栏并检查现场；不得跳过 GTID 等待或直接改只读。回执过期只能重新检查旧主并重新生成。
4. 将提升回执送回 Linux2，执行：

   `python3 mysql-guarded-recovery.py rejoin --state /var/lib/wao-mysql-recovery --evidence <提升回执> --maintenance-confirmed`

   等待 wao-return 的 IO/SQL 线程为 Yes、Source_UUID 与新主一致、wao.% 过滤存在、延迟归零。验证旧 wao_app 仍锁定；工具不自动解锁。
5. 两节点共享部署环境原子设置 `MYSQL_WRITER_HOST=10.66.0.3`、`MYSQL_WRITER_PORT=3306`、`WAO_MYSQL_PRIMARY=linux1`。Linux2 共享环境是后续 Jenkins 发布的来源，不得遗漏它。
   Jenkins 的 V77 发布状态检查和同步暂停操作也必须遵循 MYSQL_WRITER_HOST/PORT，不能继续按原生实例 MYSQL_HOST 访问被围栏旧库。用 `sh deploy/scripts/quiesce-wao-server.sh master rollout-status <Linux2项目目录>` 只读验证应返回 complete；不得为修复发布而解锁旧账号。
6. 使用各节点原有不可变镜像 SHA，只重建 wao-server 与 wao-agent-server 的连接池。Nacos 地址不跟随数据库地址自动修改。若 Nacos 本身不可达，按其独立恢复流程设置，不猜测备用服务可用。
7. 两节点应用健康、当前浏览器登录与任务真实保存/刷新、Agent 健康通过；验证 Linux1 写入、Linux2 回放。四库来源检查必须返回 `linux2-three-database-writer-and-verified-wao-replica`。
8. 手工运行一次日志归档和新全量，异机验证后运行隔离恢复，再结束维护记录。恢复用户写入口，保留明确的主角色、原生过滤和账号围栏；无自动回切。

## 出错时

### 当前 Linux1 写主的后续故障处置

本次批准只覆盖 Linux2→Linux1，当前保持 Linux1 写主。以下反向步骤需要另一个明确维护窗口，不因脚本存在而自动执行：

1. 暂停特权WAO写入并取得维护锁；确认Linux2返回副本与其他三个库健康、最新备份可恢复。
2. 在Linux1执行 `fence-linux1 --state ~/.local/state/wao-mysql-recovery --maintenance-confirmed`。脚本锁定应用账号、结束连接，并持久设置全实例只读（Linux1此实例专用于WAO）。生成独立类型的围栏回执。
3. 在两分钟内把回执送到Linux2，执行 `promote-linux2 --state /var/lib/wao-mysql-recovery --evidence <回执> --maintenance-confirmed`。它校验来源UUID、返回复制线程、过滤和GTID屏障；只删除wao-return通道并解锁本机WAO应用账号，不停其他三个库。
4. 更新两机共享环境MYSQL_WRITER_HOST=10.66.0.2、MYSQL_WRITER_PORT=3306、WAO_MYSQL_PRIMARY=linux2，并删除LINUX2_MYSQL_WRITER_HOST/PORT与MYSQL_RETURN_SOURCE_HOST/PORT覆盖；否则应用仍会连接已围栏的Linux1通道。以实际不可变镜像逐台重建App/Agent连接池并验收真实登录/保存。
5. Linux1保持账号锁定与只读，必须从Linux2新主一致性快照重新建立WAO副本；禁止直接AUTO_POSITION回接旧历史。提升回执明确标记old_source_requires_reseed，不调用原方向rejoin工具。副本重新校验之前报告数据保护降级。

若Linux1整机离线，无法产生新在线围栏回执，工具会拒绝这种自动提升；必须取得物理隔离证据并由操作人核对最后GTID/潜在丢失范围，再制定应急恢复操作。不得伪造时间新鲜的回执、复用旧回执或把无法隔离当作已经隔离。离线灾难的人工耗时未实测，不承诺30分钟一定恢复。

2026-09-21新增反向流程已在两个无生产网络、无生产卷的临时MySQL9.7实例通过：先完整执行原方向围栏/提升/返回复制，再围栏Linux1、追平并恢复Linux2 WAO写入；其他库标记保留，旧Linux1写入被拒绝，两端重启后账号锁与只读角色保持。整项实验41.473秒，临时容器/卷/网络已清理。这是工具隔离演练，未在生产执行反向切换。

提升之前：若确认 Linux1 从未变为可写，可在独立核验后恢复原状态并解锁旧账号；不能仅依赖某个命令退出码。

提升之后：优先修复新主和应用连接。不得直接把旧账号解锁、把地址改回旧主，或执行删除副本卷的 bootstrap。新主已接受写入时，回切必须重新围栏、追平并另行批准。

Linux2 整机不可达：本脚本不能生成在线围栏证据，先核验物理隔离与最后可恢复事务范围。没有隔离证据不提升；页面维护比双主写入更安全。

Linux2 在已切换后离线：WAO 可继续使用 Linux1，但另外三个库与异机备份受影响，必须报告数据保护降级，不能显示“全部恢复点正常”。

## 历史 GTID 导致回接失败的修复边界

2026-09-21 首次实机接管中，Linux1 本地初始化 GTID 被回放到已围栏的 Linux2，清空旧 WAO 副本表，随后回放会话表事务失败。Linux1 权威数据仍完整，其他三个库未停止。原隔离演练没有构造这种历史，不能据此证明实际初始化历史安全。

处理时立即停止返回通道并保持旧 WAO 账号锁定，从新主生成 `--single-transaction --source-data=2 --set-gtid-purged=COMMENTED --add-drop-database --databases wao` 一致性快照，校验传输 SHA-256 后仅恢复旧实例的 WAO 库。`COMMENTED` 保留快照 GTID 和恢复会话的 `sql_log_bin=0`，但不会自动把与现有三库写入历史重叠的整套 GTID 应用到 Linux2。恢复必须完整成功，再计算快照 GTID 与 Linux2 已执行集的差集；仅确认已由完整快照涵盖且目标尚未执行的差集才能追加到 `gtid_purged`，随后恢复复制。不得跳过未知失败事务，不得清除整实例 GTID，也不得重置其他三个库。

修复后检查表数量、真实任务保存/刷新、返回通道双线程、复制屏障和账号围栏，重新生成四库全量并隔离恢复。副本受损到重建完成的区间不能承诺原连续日志具有完整 WAO 恢复能力，必须记录这段降级。补充测试必须包括“当前数据一致但存在本地 CREATE/DROP 历史”的负例。

### 当前 Linux1 写主、Linux2 `wao-return` 停于 1062 的重建准备

以下步骤在获批的生产数据操作窗口内执行；此前只允许备份、检查与隔离演练。Linux1 继续承担 WAO 写入，Linux2 的 `tools`、`wayuwa`、`tkwork` 继续服务。`mysqldump --source-data=2 --single-transaction` 在开始时会短暂取得全局读锁以记录一致坐标，须观察用户写入延迟；若无法迅速取得锁或出现明显业务影响则中止。[MySQL 9.7 mysqldump 文档](https://dev.mysql.com/doc/refman/9.7/en/mysqldump.html)

1. 持有 Linux2 基础设施维护锁，暂停并发发布；分别确认 Linux1 权威 WAO 库、Linux2 另外三库与旧 WAO 副本的加密备份已在异机校验，且权威库和另外三库的隔离恢复实际成功。记录两端 UUID、`gtid_executed`、`gtid_purged`、通道来源/过滤/自动定位和三库表数，不把凭据写入操作记录。确认 Linux1 binlog 能保留到快照导入和追平结束。
2. 在维护窗口从 Linux2 执行 `sudo WAO_SNAPSHOT_RATE_BPS=524288 bash deploy/backup/create-authoritative-wao-snapshot.sh`。工具在 Linux1 验证声明写主、实际可写状态与 UUID，以 `mysqldump --single-transaction --quick --source-data=2 --set-gtid-purged=COMMENTED --add-drop-database --routines --events --triggers --hex-blob --no-tablespaces --databases wao` 生成只含 `wao` 的新快照，在源端 gzip 后限制传输速率，最后在 Linux2 私有目录加密、解密验 gzip、复核 SHA-256 和恢复坐标。此前未压缩的在线传输曾占满 Linux1 上行，令业务超时；本工具即使有带宽限制，也须观察业务延迟并在受影响时中止。另行人工复核 SQL 注释中的快照 GTID、binlog 坐标与 `recovery-point.json` 的 UUID；旧的无坐标保护副本不能代替本快照。快照缺少任何一项即中止。快照的 GTID 注释不自动执行。[MySQL 9.7 部分数据库转储说明](https://dev.mysql.com/doc/refman/9.7/en/mysqldump-sql-format.html)
3. 仅停止 Linux2 `wao-return` 通道，确认旧 `wao_app` 仍锁定，其他三个库继续可读写。用快照自带的 `sql_log_bin=0` 会话导入整套 `wao`，包含明确的 `DROP DATABASE IF EXISTS wao`；不得删除其他库、重置整实例 GTID 或跳过出错事务。导入失败时维持通道停止并从独立备份制定恢复，不把不完整的 WAO 库宣称为副本。
4. 从快照中提取一致时刻的 GTID 集合，在 Linux2 计算 `GTID_SUBTRACT(快照 GTID, @@GLOBAL.gtid_executed)`；人工核对差集只包含快照已覆盖且目标未执行的事务，再以 `+` 形式追加该差集到 `@@GLOBAL.gtid_purged`，差集为空则不修改。不能直接执行 mysqldump 的整套 GTID 注释，不能覆盖 Linux2 原有三库事务历史。[MySQL 9.7 gtid_purged 约束](https://dev.mysql.com/doc/refman/9.7/en/replication-options-gtids.html)
5. 保持原来源、`wao.%` 过滤和 `SOURCE_AUTO_POSITION=1`，启动 `wao-return`。校验 IO/SQL 均为 Yes、来源 UUID 正确、`WAIT_FOR_EXECUTED_GTID_SET` 对当前写主屏障返回 0、延迟归零、WAO 表/关键行一致，且另外三库表数及读写身份未改变。随后重新生成正常四库加密备份，在异机校验并隔离恢复；全部通过后才允许对象存储迁移继续。若任一检查失败，停止在当前阶段，不执行 MySQL 正文清理。

2026-09-26 只读现场核对：Linux2 `wao-return` 的 IO=Yes、SQL=No，首个失败 GTID 为 Linux1 UUID 的 `380012`；现场源/目标已执行集相减得到同一 UUID 的 `380012-539667`（会随新写入继续增长，不能当成正式快照坐标）。Linux1 当前 `binlog_expire_logs_seconds=2592000`，即 30 天，正式操作前仍须重新核对。隔离 MySQL 9.7 验证了在目标已有其他库本地事务时，追加不重叠 GTID 后原库行仍在、该 GTID 集进入 `gtid_executed`；`mysqldump --set-gtid-purged=COMMENTED --add-drop-database` 的只读格式检查也确认 `sql_log_bin=0` 位于 `DROP DATABASE` 前。以上不等于生产重建完成，也不替代新快照的 GTID 与恢复演练。

## 2026-09-21 实机前置验收

- Linux1 缺失的 wao_app、wao_repl 已使用既有私有配置补齐；备用库始终保持 read_only/super_read_only，Linux2 应用身份连接 Linux1 成功，零行 UPDATE 被1290拒绝。
- 原生 MySQL 的返回通道过滤已预先写入包含文件，`--validate-config` 成功，未重启或改生产复制方向。
- 隔离双 MySQL 9.7 直接调用本操作单核心函数：旧账户围栏、新主屏障、提升、WAO反向复制、备份来源屏障及两端重启后保护均通过。26秒完成并清理临时容器/网络/卷。
- 7项安全单元测试、11项部署契约测试、Shell语法检查通过；新部署校验脚本对实际Linux1默认副本角色验证通过，复制延迟0、Gateway健康。
- 上述前置验收时生产写主仍是 Linux2；随后用户明确批准维护窗口，10:38:55 提升 Linux1，10:51:47 双机应用、任务保存/刷新及修复后的返回复制验证通过并释放维护锁。持锁13分18秒，不等同用户保存实际中断时间。切主后全量备份和隔离恢复结果以执行记录为准，不能以本节代替。
