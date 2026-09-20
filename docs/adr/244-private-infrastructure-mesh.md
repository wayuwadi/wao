# ADR-244：三节点基础设施使用直连 WireGuard 私网

## 状态与边界

已采纳，补充 ADR-005 的公网 DDNS 拓扑。公共 HTTPS 入口保持原样；
本阶段只建立内网，不改变 Redis/MySQL 主从角色或 Sentinel quorum，不自动启用故障提升。

## 决策

复用云机与 Linux2 的既有 wg0，添加 Linux1 独立身份和 wg-wao 接口。
云机10.66.0.1、Linux2 10.66.0.2、Linux1 10.66.0.3。
Linux1与Linux2使用公网IPv6直接建立认证加密通道；云机不转发两家之间的数据库/复制数据。
没有全局默认路由，也不把两家整个局域网互通：仅路由对方WireGuard /32及服务主机LAN /32。

- Linux1到Linux2：10.66.0.2/32、192.168.31.180/32。
- Linux2到Linux1：10.66.0.3/32、192.168.2.180/32。
- 云机分别通过对应peer访问两台主机，不能用云机peer承接另一Linux的数据路径。

Linux1现有桌面TUN会捕获默认出站流量。只给WireGuard自己的加密UDP设置FwMark=0x6603，
IPv4/IPv6优先级8899规则按该标记查询主路由表；普通流量仍保持原来的TUN策略。
先拒绝接口或规则编号冲突，不覆盖其他VPN配置。MTU1380；keepalive25秒。
机制依据：[WireGuard官方快速开始](https://www.wireguard.com/quickstart/)。

Linux1借助一次性工具容器操作已存在的宿主内核模块；工具只授予NET_ADMIN、读取配置所需能力，
只读挂载专用目录，不挂载宿主根目录或Docker socket，不需要启动长期VPN用户态容器。
实际构建复用本机可信Redis Alpine镜像作为工具基础层，不启动Redis服务；默认Dockerfile仍为Alpine。
镜像本地构建后经既有SSH直传另一Linux，不通过云机传输。

两节点每分钟核对对方AAAA和握手，保留仍有效的地址，必要时刷新同一公钥对应的端点。
DNS只决定传输地址，不能替换身份或AllowedIPs；仅接受global IPv6。
Linux1遇到TUN合成DNS时使用验证证书的公共DoH获取AAAA，失败保留现有peer。
私钥、实际peer配置和配置备份仅保存在各机受限目录，不进入仓库。

## 实机验收（2026-09-20）

- 两Linux互通，单次ICMP样本约6–7ms；Linux1到云机约25ms。初次配置后的握手收敛阶段出现丢包，
  后续TCP不能仅靠接口存在判定就绪。
- 两方向从实际Redis容器，通过对方私有LAN地址认证Redis和Sentinel，四项PING均返回PONG。
- 云机到两台私有Sentinel端口TCP连接成功。
- Linux1用户服务重启后恢复接口、路由及握手；两边地址刷新服务退出0，定时器启用。
- 只读ECHO测试4×256KiB，双向总有效载荷2MiB，约1.108秒（聚合15.1Mbps）；未持久化测试数据。
  该结果不是对任一家运营商上行带宽的保证，更不能把“1000M宽带”当作对称上行。
- 普通公网路由仍由原TUN接管；只增加指定peer地址。临时peer回滚计时器已停止。

## 限制

尚未实测路由器换公网地址、整机断电或双方IPv6同时失效。端点更新有DNS和一分钟计时延迟。
部署Redis切主前仍须审查客户端、锁/幂等和启动角色，不能因私网可达就直接降低quorum。
私网路由配置恢复步骤见 `docs/runbooks/private-node-mesh.md`。
