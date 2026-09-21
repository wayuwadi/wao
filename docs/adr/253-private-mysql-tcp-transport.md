# ADR-253：为 Linux2 到 WAO 新写主提供私网 TCP 通道

状态：采用。范围只覆盖 Linux2 的 MySQL 应用连接和 wao-return 返回复制；数据库权威角色不变，WireGuard 保留控制面。

## 实测依据

2026-09-21 同一20MiB数据从Linux1到Linux2：原生SSH3.301秒，WireGuard内SSH66.436秒；隧道内TCP观测约20%字节重传，7–11ms小包ping正常。实际MySQL读取同一测试附件，原WireGuard路径48.114秒，临时私网SSH转发3.200秒。这证明当前隧道大流量表现存在问题，不能把1000M套餐当成可用上行实测值；尚未断言运营商或MTU是根因。

## 决策

- Linux2以既有严格SSH身份和known_hosts连接Linux1，systemd维持TCP通道，固定转发到Linux1本机3306。
- 仅监听Linux2的127.0.0.1:13306和10.66.0.2:13306，禁止0.0.0.0、公网监听和路由器映射；主站、Gateway和家庭用户都不接触数据库端口。
- 两节点共享MYSQL_WRITER_HOST仍表示权威写主10.66.0.3。Linux2应用/Agent及发布维护查询允许LINUX2_MYSQL_WRITER_HOST/PORT覆盖为10.66.0.2:13306，Linux1保持本机直连。
- 旧实例wao-return只更改连接端点127.0.0.1:13306，不清空GTID/表、不重建通道、不解锁旧wao_app。MYSQL_RETURN_SOURCE_HOST/PORT告诉备份检查期望传输端点，检查仍通过独立写主连接核对Source_UUID、GTID屏障、线程和延迟。
- SSH心跳10秒、失败3次退出、5秒重启；128MiB和半核预算。断线期间Linux2应用可能暂不可用，浏览器仍可选Linux1。该服务不具备选主或自动回切权限。

## 运维与回退

私有文件/etc/wao-mysql-recovery/tunnel.env只保存在节点，含MYSQL_PRIMARY_SSH_HOST、SSH_KEY、KNOWN_HOSTS，不进入Git。安装deploy/network中的脚本和unit，启用wao-mysql-primary-tunnel.service。先验证监听地址、真实账号查询server_uuid和样本吞吐，再配置节点覆盖并滚动重建应用连接池。

四库加密快照额外包含mysql-tunnel-config.tar.gz，保存服务、脚本、私有连接配置及所引用的SSH身份/known_hosts；只在0700暂存目录生成，随整体载荷加密。恢复时先核对目标机器身份和当前写主，不直接启动历史通道或覆盖当前角色。

返回复制改变传输需先STOP REPLICA、仅CHANGE SOURCE_HOST/PORT、START REPLICA，随后验证同一个Source_UUID、GTID追平和旧账号围栏。保留原私有环境备份。传输回退撤销Linux2覆盖并将复制端点改回10.66.0.3:3306；这不是数据库角色回切，不解锁旧账号，也不改变Linux1可写状态。

媒体大文件仍须做浏览器和真实网络验收。此数据通道提速不能代替公网下载的实测，也不承诺家庭上行1000M或所有用户相同速度。
