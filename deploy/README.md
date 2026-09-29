# WAO 部署说明

当前部署拓扑见 [ADR-291](../docs/adr/291-linux2-only-deployment-and-wao-recovery.md)：Linux2 是唯一 Linux 应用与 Jenkins 节点；云服务器负责 Nginx、TLS、静态网页、公共入口检查和异地加密备份。原双 Linux 节点流程已退役。

| 节点 | 当前职责 |
| --- | --- |
| Linux2（linux2.wayuwa.cn） | WAO Server、Agent、Gateway、数据服务与 Jenkins |
| 云服务器 | Nginx/TLS、网页静态制品、公共就绪探针、异地备份 |

## 发布

主分支 push 触发 Jenkins 批处理发布：

- 纯浏览器变更只发布云端不可变静态制品，保留当前 Linux2 后端。
- 后端或部署变更先检查 Linux2 与公共入口，再更新 Linux2 应用并发布云端静态制品。
- 发布成功要求 Linux2 Gateway、云端静态资源和公共 /api/edge/ready 检查通过。
- Linux2 是唯一应用节点，原地更新可能短暂中断；当前没有应用节点自动故障转移。
- 飞书成功卡仅表示本次发布阶段的检查通过，不代表其他业务功能已经逐项验收。

日常部署使用 Jenkins 主发布链。Linux2 Compose 的受控运维入口为 deploy/scripts/deploy-linux.sh master。不要重新启用退役的双节点脚本、旧 peer 定时任务或 Linux1 SSH 凭据。

## 配置与凭据

真实环境文件只保存在 Linux2 的私有部署目录和系统凭据存储中：

- deploy/.env 与 deploy/.env.secrets 不得提交。
- Jenkins SCM、云端 SSH、GitHub 状态回写凭据只保存在 Jenkins Secret 文件和 Credentials 中。
- DNS、数据库、对象存储与备份密钥不得出现在仓库、命令行参数或构建日志中。
- Linux2 Gateway 证书和数据库恢复密钥按对应运维手册单独管理。

## 服务边界

- WAO Server 使用 Linux2 本机 Redis 服务直连；当前不依赖 Redis Sentinel 自动切换。
- Nginx 的 WAO Gateway 上游仅指向 Linux2。
- JumpServer 的当前 WAO 资产为 Linux2 与云入口；旧主机、隧道网关和授权会在资产同步时停用。
- 监控只探测公共入口与 Linux2。
- Garage 对象存储曾按三节点布局配置。Linux1 退役后的 Garage 数据布局尚需单独确定；旧三节点配置不能作为现行拓扑操作手册。更改副本数前必须先完成 Garage layout 迁移方案和恢复验证。

## 备份与恢复

当前数据库异地备份链、恢复前置条件和已完成的隔离恢复证据见[Linux2 单节点异地备份](../docs/runbooks/linux2-only-cloud-backup.md)。Peer 备份定时任务已停用，原快照仍保留；停用任务不等于删除数据。

发生数据库或节点恢复时，按 [ADR-291](../docs/adr/291-linux2-only-deployment-and-wao-recovery.md) 的数据安全门槛执行。不要把历史 Linux1 主从或 failover 手册当作当前步骤。

## 历史记录

以下材料记录过往拓扑，仅用于追溯，不是当前部署说明：

- [Phase 0 历史上线记录](../docs/runbooks/phase-0-rollout.md)
- [Linux peer 备份历史记录](../docs/runbooks/linux-peer-backup.md)
- [旧双节点 ADR](../docs/adr/157-linux1-native-slave-and-development-node.md)
