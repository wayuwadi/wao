# ADR-245：Redis 与 Sentinel 的运行拓扑跨重启保留

## 决策

在启用第三 Sentinel 前，先修复启动时覆盖拓扑的问题。此前 Linux1 每次启动强制
`--replicaof` 原主节点，Sentinel 每次启动生成 `/tmp/sentinel.conf`，发布还强制重建 Sentinel。
这会丢失提升后的角色、Sentinel 身份和选举纪元，不能作为自动故障转移的基础。

Redis 从既有数据卷的 `/data/wao-runtime/redis.conf` 启动；Sentinel 使用独立持久卷的
`/data/sentinel.conf`。运行账号可写配置目录，支持原子 `CONFIG REWRITE` / Sentinel 自身重写。
配置权限600、目录700，运行进程使用镜像内 redis 用户。普通发布不再强制重建 Sentinel，
容器因配置变更重建时仍读取持久状态，环境变量不覆盖已选主节点。

缺少状态文件时拒绝启动，不根据主机名称或旧环境变量猜测主从，不自动初始化空卷。
丢失整个卷与首次安装不能自动区分，必须人工核对仍存活的集群后准备配置。

迁移脚本默认只读检查。明确传入 `--prepare` 后，从两节点原有进程读取实时角色、鉴权、
限制及 announce 参数，刷新并复制 Sentinel 原有状态；拒绝 quorum<3、已出现第三 Sentinel、
主库不健康、基线角色变化或目标文件已存在。凭据只在内存及受限卷中流转，不放入命令参数或日志。
迁移不重启服务、不改角色、不改 quorum；部署前应先完成两机准备。
两机每日加密备份同时导出运行配置和角色快照；任一导出失败不发布成功标记。
这些状态只供受控恢复参考，不能用历史Sentinel纪元覆盖仍存活的集群。

本阶段仍为两个 Sentinel、quorum=3，不能自动切主。第三 Sentinel、私网 announce、
客户端恢复及故障演练另行完成，不能将本变更描述为 Redis 高可用验收通过。

依据：[Redis Sentinel 官方文档](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)
明确要求可写配置以保存并重新载入状态；[Redis 配置文档](https://redis.io/docs/latest/operate/oss_and_stack/management/config/)
说明运行时变更需通过 CONFIG REWRITE 持久化。

## 验证

使用现网已有 Redis 7 Alpine 镜像，隔离网络、临时数据目录、无宿主端口：
缺少状态拒绝启动；降为从节点后重启仍为从；提升为主后重启仍为主且数据保留；
Sentinel 身份和更改后的监控地址重启后不变。测试不访问生产键。

现网两节点采用前检查已通过，角色仍为 Linux2 master / Linux1 slave，quorum=3。
实机切换入口后的验收及恢复边界记录在运行手册，不以隔离测试替代真实迁移结果。
