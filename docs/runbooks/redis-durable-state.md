# Redis 持久拓扑迁移与恢复

适用范围：WAO 原有两 Redis、两 Sentinel；不涉及 JumpServer、WeKnora 或其他 Redis。

## 迁移顺序

1. 确认两机角色、复制在线、无切主过程，第三 Sentinel 尚未启动，quorum仍3。
2. 在对应 Docker 主机执行 `python3 deploy/redis/prepare-durable-state.py master` 或 `slave`。
   Linux2需以有Docker权限的管理身份运行；输出只包含角色和quorum。
3. 同命令加 `--prepare`，准备两机状态。已存在目标文件则拒绝覆盖；部分准备失败应先检查
   两个目标，不得直接清空数据卷重试。
4. Linux1先部署新入口，验证复制在线、Sentinel身份不变及 `CONFIG REWRITE` 成功；
   再部署Linux2，验证主库仍为master、一个副本online、两应用健康和真实任务列表读取。
5. 额外执行一次受控重启，验证角色、Sentinel身份与配置路径保留。

Redis状态在原有 `wao-master_redis-master-data` / `wao-slave_redis-slave-data` 中。
Sentinel新增卷 `wao-master_sentinel-master-data` / `wao-slave_sentinel-slave-data`。
不要 `docker compose down -v`；配置含鉴权值，不输出、不上传明文、不提交Git。

## 配置调整与回退

- 已采用持久状态后，环境变量中的初始主节点、quorum和密码不再自动重写配置。
  修改需走受控运行命令、持久化并核对所有客户端；只改env不是改完。
- 此阶段未改变主从角色，若入口迁移失败，可恢复旧Compose/入口后只重建对应Redis或Sentinel。
  **一旦发生选主或角色改变，禁止直接回退旧启动逻辑**，应保留新状态修复服务。
- 状态文件丢失时服务拒绝启动；先查明仍存活的权威主库，再从受保护配置备份恢复或受控重建。
  历史Sentinel快照不得未经核对就覆盖当前选举纪元。
- 每日两机加密备份新增两份 Redis 配置归档，包含运行配置、Sentinel状态与角色快照；
  导出失败会阻止发布新备份。归档是重建参考，不可将历史角色/选举纪元直接覆盖现存集群。
  Redis业务键不在此配置归档内，临时状态仍有异步复制丢失窗口。

## 隔离回归命令

```sh
docker run --rm --network none --memory 192m --cpus 0.5 --pids-limit 128 \
  --tmpfs /data:rw,size=64m -v "$PWD/deploy/redis:/opt/wao:ro" \
  redis:7-alpine sh /opt/wao/test-durable-state.sh
```

## 2026-09-20 实机采用

两节点准备与迁移已完成；Linux1先重建Redis/Sentinel，复制up、sync_in_progress=0，
随后Linux2重建，master、一个副本online、lag=0。两节点CONFIG REWRITE均OK，Sentinel MYID前后相同。
公开项目页面重新加载后显示真实任务与子任务；测试任务#1152删除后仍不存在。

另行生成配置快照20260920T110502Z：Linux2 `/var/backups/wao-redis-state/20260920T110502Z`，
Linux1 `/home/wage/backups/wao-redis-state/20260920T110502Z`，密文校验一致；Linux1解密核对两份配置归档结构成功。
这不是Redis业务键恢复，也不是自动切主演练。后续每日完整备份的format=2会携带两机运行状态。
