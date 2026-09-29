# Garage 对象存储状态与迁移决策

## 当前状态

[ADR-291](../../docs/adr/291-linux2-only-deployment-and-wao-recovery.md) 已将 Linux1 退役。仓库旧示例仍描述 Linux1、Linux2 和云机三节点、replication_factor 为 3 的 Garage 布局；它已不是可直接执行的当前部署手册。

本次只清理 Linux1 部署入口，没有检查或改写在线 Garage 布局、节点数据、bucket、key 或生产对象。拓扑迁移须先核对实际 garage status 和 garage layout show，再基于可恢复备份制定单独步骤。不要照旧示例启动节点、调整副本数或执行 layout apply。

## 副本数的含义

Garage 的复制因子表示每个对象在 Garage 集群中保存的副本数。副本数为 2，意味着每个对象分布到两个已分配存储节点；它不是两份独立备份，也不会自动把数据复制到一个独立备份系统。Garage 的布局变更会触发数据传输，应先检查计划并使用未重复的 layout 版本号。

考虑到目前 Linux2 是主要机器、云机容量小且性能较弱，我建议的目标是 Linux2 单节点 Garage（副本数 1），并把云端作为独立、可恢复验证的备份目的地。这样云机不进入日常对象读写路径，但 Garage 节点故障时没有第二个在线 Garage 副本，需要依靠备份恢复。另一种方案是 Linux2 + 云机、副本数 2；每次对象写入都依赖两台存储节点，云机的空间和性能会成为容量与延迟约束。该方案尚未选定。

## 旧示例

- [garage.toml.example](garage.toml.example) 和 [garage-compose.yml](garage-compose.yml) 仅保留作历史格式参考；其中旧的三节点副本数和地址不可直接用于当前部署。
- 应用环境中的对象存储 endpoint 与数据库对象数据均未在本次变更中迁移。
- 完成拓扑选择后，应单独制定迁移方案，验证布局变更、对象读回、独立备份恢复和应用链路，再更新本页与部署配置。

参考：[Garage 实际部署建议](https://garagehq.deuxfleurs.fr/documentation/cookbook/real-world/)、[Garage 布局操作](https://garagehq.deuxfleurs.fr/documentation/operations/layout/)。
