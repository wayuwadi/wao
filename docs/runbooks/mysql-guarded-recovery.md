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
6. 使用各节点原有不可变镜像 SHA，只重建 wao-server 与 wao-agent-server 的连接池。Nacos 地址不跟随数据库地址自动修改。若 Nacos 本身不可达，按其独立恢复流程设置，不猜测备用服务可用。
7. 两节点应用健康、当前浏览器登录与任务真实保存/刷新、Agent 健康通过；验证 Linux1 写入、Linux2 回放。四库来源检查必须返回 `linux2-three-database-writer-and-verified-wao-replica`。
8. 手工运行一次日志归档和新全量，异机验证后运行隔离恢复，再结束维护记录。恢复用户写入口，保留明确的主角色、原生过滤和账号围栏；无自动回切。

## 出错时

提升之前：若确认 Linux1 从未变为可写，可在独立核验后恢复原状态并解锁旧账号；不能仅依赖某个命令退出码。

提升之后：优先修复新主和应用连接。不得直接把旧账号解锁、把地址改回旧主，或执行删除副本卷的 bootstrap。新主已接受写入时，回切必须重新围栏、追平并另行批准。

Linux2 整机不可达：本脚本不能生成在线围栏证据，先核验物理隔离与最后可恢复事务范围。没有隔离证据不提升；页面维护比双主写入更安全。

Linux2 在已切换后离线：WAO 可继续使用 Linux1，但另外三个库与异机备份受影响，必须报告数据保护降级，不能显示“全部恢复点正常”。

## 历史 GTID 导致回接失败的修复边界

2026-09-21 首次实机接管中，Linux1 本地初始化 GTID 被回放到已围栏的 Linux2，清空旧 WAO 副本表，随后回放会话表事务失败。Linux1 权威数据仍完整，其他三个库未停止。原隔离演练没有构造这种历史，不能据此证明实际初始化历史安全。

处理时立即停止返回通道并保持旧 WAO 账号锁定，从新主生成 `--single-transaction --source-data=2 --set-gtid-purged=ON --databases wao` 一致性快照，校验传输 SHA-256 后仅恢复旧实例的 WAO 库。恢复必须完整成功，再计算快照 GTID 与旧实例已执行集的差集；仅确认已由完整快照涵盖的差集才能纳入已执行历史，随后恢复复制。不得跳过未知失败事务，不得清除整实例 GTID，也不得重置其他三个库。

修复后检查表数量、真实任务保存/刷新、返回通道双线程、复制屏障和账号围栏，重新生成四库全量并隔离恢复。副本受损到重建完成的区间不能承诺原连续日志具有完整 WAO 恢复能力，必须记录这段降级。补充测试必须包括“当前数据一致但存在本地 CREATE/DROP 历史”的负例。

## 2026-09-21 实机前置验收

- Linux1 缺失的 wao_app、wao_repl 已使用既有私有配置补齐；备用库始终保持 read_only/super_read_only，Linux2 应用身份连接 Linux1 成功，零行 UPDATE 被1290拒绝。
- 原生 MySQL 的返回通道过滤已预先写入包含文件，`--validate-config` 成功，未重启或改生产复制方向。
- 隔离双 MySQL 9.7 直接调用本操作单核心函数：旧账户围栏、新主屏障、提升、WAO反向复制、备份来源屏障及两端重启后保护均通过。26秒完成并清理临时容器/网络/卷。
- 7项安全单元测试、11项部署契约测试、Shell语法检查通过；新部署校验脚本对实际Linux1默认副本角色验证通过，复制延迟0、Gateway健康。
- 上述前置验收时生产写主仍是 Linux2；随后用户明确批准维护窗口，10:38:55 提升 Linux1，10:51:47 双机应用、任务保存/刷新及修复后的返回复制验证通过并释放维护锁。持锁13分18秒，不等同用户保存实际中断时间。切主后全量备份和隔离恢复结果以执行记录为准，不能以本节代替。
