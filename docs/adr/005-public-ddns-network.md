# ADR-005：三节点使用公网与 DDNS 直连

## 状态

已采纳，取代 ADR-002 与 ADR-004。

## 上下文

主、从节点位于动态公网地址之后，云节点具有固定公网地址。项目所有者明确决定不使用 Tailscale 或其他覆盖网络，接受当前阶段将节点服务通过公网互联的风险，以减少客户端软件和网络依赖。

## 决策

- 主节点使用 `linux2.wayuwa.cn`，从节点使用 `windows1.wayuwa.cn`，云节点使用固定公网地址。
- 两个家庭路由器将 SSH、Gateway、MySQL、Redis、Sentinel 和 DDNS-Go 所需端口转发到对应主机；主节点额外转发 Nacos 端口。
- GitHub Actions 通过公网直接 SSH 到三个节点，使用专用 ED25519 密钥，并通过 `DEPLOY_SSH_KNOWN_HOSTS` 固定校验三台机器的 SSH 主机密钥。
- Nginx 通过两个 DDNS 域名访问 Gateway；Redis 与主、从两个 Sentinel 宣告可解析的公网主机名；应用向 Nacos 注册当前公网 IPv4。
- 云节点只运行 Nginx、Certbot 和静态前端，不安装 Docker，不承载数据库、缓存或 Sentinel。
- DDNS 负责更新域名。主、从节点每五分钟检测公网 IPv4 变化，并在变化时重建应用与网关容器，刷新 Nacos 注册；云 Nginx 定期重载以重新解析上游域名。
- 对外用户入口只使用 `https://wao.wayuwa.cn`。Gateway 节点间链路继续使用项目自签 TLS；密码和令牌使用随机强值并保存在节点私有 `.env` 或 GitHub Secrets。

## 后果

- 不再需要安装、登录或维护 Tailscale，也不需要通过云服务器 `ProxyJump`。
- MySQL、Redis、Nacos、Sentinel、Gateway、DDNS-Go 和 SSH 会按路由器及云防火墙规则暴露到公网，攻击面显著大于私网方案；这是项目所有者在当前阶段明确接受的权衡。
- 动态公网 IP、运营商 CGNAT、路由器不支持回环解析或端口映射时，跨节点部署会失败。必须先验证 DDNS 解析、端口映射和三台机器之间的双向连通性。
- 两个 Sentinel 无法在任一整机离线后形成可靠多数派，因此当前不承诺 Redis 整机故障自动切换；启用该能力前必须增加独立第三仲裁节点。
- 后续进入正式生产前，应重新评估源地址白名单、VPN/专线、数据库 TLS、SSH 防爆破和最小端口暴露。
