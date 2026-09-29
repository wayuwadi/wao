# Redis 私网三 Sentinel 运维

> 历史运维手册：本文的多 Sentinel / 双 Redis 操作依赖已退役的 Linux1。当前部署不使用该故障转移流程；请以 [ADR-291](../adr/291-linux2-only-deployment-and-wao-recovery.md) 为准。

相关决定：ADR-244、245、246。所有命令在有现有Docker管理权限的对应Linux运行；不打印配置或凭据。

## 分阶段部署

1. 先部署ADR-245的持久配置，确认两机CONFIG REWRITE成功、复制在线。
2. 两机分别执行 `adopt-private-topology.py master gate` / `slave gate`，确认quorum4持久化。
3. 云机仅安装Ubuntu源的redis-sentinel包；先mask包自带redis-sentinel.service，避免安装后自动启动。
   Linux2以管理身份执行 `prepare-cloud-witness.py`，配置经既有Jenkins身份和严格主机校验送达云机。
   安装 `wao-cloud-sentinel.service`，此时不启动。
4. 两机依次执行 `adopt-private-topology.py slave adopt` / `master adopt`，不改变主从角色。
   此操作重建监控条目以清除旧公网地址，但保留Sentinel MYID；quorum始终4。
5. 云机启动并启用自定义服务；不要unmask包自带服务。双方必须通过私网访问10.66.0.1:26379。
6. 两机执行 `validate-private-ha.py <role> --quorum 4`，核对所有Sentinel身份、私网发现地址、一致监控、实际主从。
7. 共享环境设置：
   - REDIS_MASTER_HOST=192.168.31.180（仅历史初始化元数据，不代表当前写主）
   - REDIS_SENTINEL_NODES=192.168.31.180:26379,192.168.2.180:26379,10.66.0.1:26379
   - REDIS_HA_QUORUM=4
   MASTER_PUBLIC_HOST/SLAVE_PUBLIC_HOST仍是公开HTTPS域名，不随Redis变更替换。
8. 按既有发布流程让两应用采用三个私网Sentinel；只改变env文件不等于进程已采用。
   发布前可用下述同库探针验证私网读写；发布后核对实际容器中的节点清单和健康。
9. 客户端验收后，才对三个Sentinel执行SENTINEL SET wao-master quorum 2并FLUSHCONFIG，
   共享环境REDIS_HA_QUORUM也改为2，最后两机执行 `validate-private-ha.py <role>`。

配置转换中任一步失败保持quorum4，不自动降级为公网或无认证。若监控条目尚未完成认证设置，
先在本机恢复对应auth-pass并FLUSHCONFIG，不启动第三个可选主Sentinel。

## 实际客户端探针

`RedisHaProbe.java`用项目依赖编译；临时class放进应用容器/tmp专用目录。
借助应用/app/app.jar中的PropertiesLauncher运行，不启动第二个Spring应用或调度器。
使用环境中既有REDIS_PASS，不把密码放命令参数。探针使用10分钟TTL专属随机键，完成时删除。

```sh
java -Xmx64m -XX:ActiveProcessorCount=2 \
  -Dloader.path=/tmp/wao-redis-ha-probe \
  -Dloader.main=cn.wayuwa.wao.ops.RedisHaProbe \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher 180
```

看到PROBE READY后才注入故障，完整记录PRIMARY_CHANGED、临时失败、READ_WRITE_OK和COMPLETE。
必须比对切换前后的runId及恢复耗时，不能只凭进程退出或Sentinel选主日志认定通过。

## 受控故障和恢复

- 只停止WAO当前Redis主容器，保留MySQL、Nacos、两应用和Sentinel运行；先设置有时限的自动恢复保护。
- 两台应用的同版本探针同时运行，观察旧连接失败、新主发现、已复制测试值保留与写恢复。
- 重启原Redis后核对它被配置为新主的从节点，CONFIG REWRITE及再次重启后的角色必须一致。
- 不强求切回Linux2；拓扑稳定时让实际主节点保持，部署校验必须接受两种角色。
- 如果原主重新加入后出现双主或旧目标，先停止旧主并保持已提升节点，不启动旧Compose/旧初始化脚本。
- 这次进程故障不能替代整机断电、网络分区或双方运营商同时故障的演练；需要分别记录。

## 恢复边界

Sentinel使用异步复制，允许暂时丢失缓存/扫码状态；持久任务与权限仍由MySQL决定。
旧主隔离时不能依靠Redis锁保证业务唯一执行。未来服务接入必须使用独立凭据/命名空间、容量预算，
关键幂等和锁围栏放数据库。

旧公网端口尚未在本步骤关闭；开发机改用私网或受控隧道后再按客户端盘点收紧，避免切断既有开发访问。
仅持有公开域名的客户端无法直达新私网目标，不提供自动明文/公网降级。

## 回退

优先把三Sentinel设回quorum4并核对一致性，保留当前已选主节点。暂停云机Sentinel不等于恢复旧拓扑。
若需要恢复旧公开发现地址，应先停止切主、核对唯一主节点并逐一调整；不能直接覆盖历史配置快照。

## 2026-09-20 灰度验收

- 两Linux已采用私网announce/复制/监控地址，三个Sentinel独立身份互见且一致确认Linux2为主。
- 两机运行validate-private-ha --quorum 4均通过；主从角色未改变。
- 云机服务MemoryCurrent约3.3MiB，仅监听10.66.0.1与127.0.0.1；默认软件包服务保持masked。
  Windows存在TUN代理，TCP连接成功不能证明公网端口开放；Redis协议请求未获响应，宿主无26379/6379 DNAT规则。
- 在两个真实应用容器中使用部署镜像依赖运行10秒探针：Linux1成功9次、Linux2成功10次，均零暂时失败；看到相同主库runId，随机键已清理。
- 共享环境已准备三私网地址与REDIS_HA_QUORUM=4；正式应用需随本批部署采用。自动切主仍关闭，故障恢复测试待启用后进行。
