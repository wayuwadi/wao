# ADR-290：公网常规流量优先走 WAO 写库所在的 Linux1

状态：采用（2026-09-27）。此决策不改变 MySQL 写主、复制方向或 VPN 专用路由。

## 证据

Linux2 的 WAO 应用仍需经跨站 SSH 隧道访问 Linux1 权威 MySQL；其本地 `wao-return`
副本 SQL 线程停在 1062，不能用于业务写入或无保护接管。云端新增的就绪回源日志在同一
观察窗口内记录：Linux2 回源 36 次有 3 次超过 2 秒，最长 4.54 秒；Linux1 WireGuard
回源 11 次均低于 0.3 秒。云端监视器曾在两个节点入口均正常时记录公网 `main=false`，
对应一次就绪探针主动断开（HTTP 499）。这些样本支持降低常规请求经过 Linux2 的概率，
但不足以证明 Linux2 是全部公网超时的唯一原因。

## 决策

云 Nginx 的普通 WAO API 上游以 Linux1 WireGuard 为常规路径；Linux2 Gateway 与
Linux1 反向 SSH 隧道保留为备用。Linux2 仍运行并接受独立就绪检查；Linux1 Gateway
不可用而写库仍可访问时，Linux2 可继续承接请求。Jenkins 逐节点摘流与回源就绪门槛
保持生效，发布时不能把备用节点也同时摘除。VPN 专用的 `wao_ai_gateway` 继续走 Linux2。

## 验收与回退

发布后检查云 Nginx 语法、两个节点容器与数据库就绪、公共入口和回源日志；正常请求
应主要走 Linux1。抽样窗口不能代替后续长时间观测。若 Linux1 路由成为瓶颈或失败率
更高，恢复两个常规 Gateway 上游并保留 ADR-287 的 Linux1 双路回源。Linux2 副本
重建和异机备份恢复仍按受控手册执行，不能因为入口短期变快就宣称双节点容灾已完成。
