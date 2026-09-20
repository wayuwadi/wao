# ADR-246：Redis 使用私网双数据节点与三独立 Sentinel

## 范围

仅管理 WAO 自身 Redis，不改变其他应用的独立实例。基于 ADR-244 私网与 ADR-245 持久状态。
初始数据主库Linux2，Linux1可被Sentinel提升；节点在部署中的master/slave标签不再决定Redis角色。

## 拓扑与启用顺序

两数据地址为192.168.31.180、192.168.2.180；Sentinel位于这两台主机及云机10.66.0.1，端口26379。
Redis复制、Sentinel发现与应用到Redis的数据连接均使用经过WireGuard的私网地址。
两Linux之间直接传输，不由云机转发。云机只有少量监控/选举流量，不承载Redis业务数据。

1. 先把两已有Sentinel的quorum升为4并持久化，保持自动切主关闭。
2. 核对原角色后设置私网announce、复制地址与监控地址，保留Sentinel身份。
3. 云机安装Ubuntu受支持软件源中的Sentinel，系统默认服务保持masked，使用单独的WAO服务。
   配置只绑定loopback/WireGuard地址，初始quorum4，配置目录700、文件600。
4. 三者互见、三个身份、同一私有主节点、一主一从且复制在线，通过检查后才允许将三者quorum调整为2。
5. 应用使用三个Sentinel地址，通过Spring Data Redis/Lettuce发现主库；不配置固定写主旁路。
   发布校验也根据三者共识检查实际角色，不再断言Linux1必须是从库。
6. 用部署镜像中的实际客户端库执行临时键读写与受控故障恢复；只观察选主结果不能作为完成证据。

## 状态与资源

Redis角色与Sentinel纪元由可写持久配置保存，修改必须持久化。云机服务依赖wg0，
MemoryMax128MiB、CPUQuota25%、TasksMax64；两Linux的Sentinel同样限制128MiB/0.25CPU/64PID。
两Redis容器既有2GiB上限保留，maxmemory设为512MiB、noeviction，为复制/AOF重写留出空间。

maxmemory限制超出后会返回写入错误，不静默驱逐登录状态；监控需在耗尽前报告。
本阶段保留min-replicas-to-write=0，允许只剩一个数据节点时继续服务，**不承诺已确认临时状态零丢失**。
旧主隔离时可能短暂接受旧连接的缓存写入；关键身份、权限、持久任务和幂等记录不放Redis。

代码审查范围内，Redis用于公众号扫码临时状态、访问令牌缓存和Skill限速。
权威WAO会话使用Spring Session JDBC；业务任务/账号持久化在MySQL。当前未发现Redis分布式任务锁。
扫码临时状态丢失时需要重新扫码；限速器既有策略为Redis错误时不阻断主业务。
这不为未来应用的锁或Exactly-once提供保证；复用基础设施前需单独审查围栏与数据库约束。

## 兼容边界

现有公网DB/Sentinel端口不能因本变更扩大开放；本阶段不会在未完成客户端盘点时直接封掉既有入口。
新客户端获得的是私网Redis地址，开发电脑需要已授权的私网访问或受控隧道；不得回退到公开无鉴权Redis。
云机失效时两Linux的Sentinel仍可形成多数派；任一Linux失效时，另一Linux和云机可形成多数派。
两数据节点同时失效无自动数据恢复；不把三Sentinel误称三个数据副本。

参考：[Redis Sentinel](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)、
[Sentinel客户端规范](https://redis.io/docs/latest/develop/reference/sentinel-clients/)。

## 验收状态

三者私网互见及一主一从已验证，部署同版本Spring Data/Lettuce探针读写通过；当前quorum=4。
应用采用三私网发现地址后，另行启用quorum=2并执行受控切主。尚未计作切主演练通过。
