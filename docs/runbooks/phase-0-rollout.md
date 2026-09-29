# Phase 0 实机上线清单

> 历史上线记录：本文记录的 Linux1 从节点、双节点复制与发布步骤已由 [ADR-291](../adr/291-linux2-only-deployment-and-wao-recovery.md) 取代。当前操作见 [部署说明](../../deploy/README.md) 和 [Linux2 单节点异地加密备份](linux2-only-cloud-backup.md)；不要重放本文旧步骤。

## 已完成

- Java 17 多模块、Gateway、Nacos、Vue 3 前端与 Hello API 纵向切片。
- Linux2 主节点、Linux1 原生 Linux 从节点及云前端节点的部署与运维脚本。
- MySQL GTID 主从、Redis 主从与双 Sentinel、DDNS-Go、Gateway TLS、Nginx、备份恢复。
- Linux 自建 Jenkins 测试、容器构建、三节点发布和飞书结果通知。
- 本机 Vite -> Gateway -> WAO Server 的 `GET /api/hello` 验证。

## Linux1 从节点（娃哥）

当前状态：旧 Windows1 物理机已重装为 Ubuntu，并使用 `linux1.wayuwa.cn`。它作为 Linux2 的从节点，同时承担日常开发和 AI Runner；旧 WSL、Windows 计划任务、RDP 与 Docker named volumes 均不存在，首次上线按全新从节点从 Linux2 重建 MySQL/Redis 副本。

在 `wage` 的 SSH 会话中准备代码和 Docker：

```bash
cd ~/workspace/wao
sudo sh deploy/scripts/install-docker-engine.sh
# 重新登录，使 docker 组生效
docker info
```

Jenkins 使用专用 Ed25519 密钥登录 `wage@linux1.wayuwa.cn`，严格校验 `slave-known-hosts`。首次发布手工启用 `BOOTSTRAP_SLAVE`，从 Linux2 生成 GTID 快照并重建 MySQL 从库；后续发布保持关闭。发布包、镜像、共享配置和证书通过 SSH 送到 `~/deploy/incoming`，由 `install-release-linux.sh` 在锁内原子安装到 `~/deploy/wao`。

MySQL GTID 从库只复制 `wao.%`，不会同步 Linux2 上的其他业务库。使用 `sh deploy/scripts/validate-slave.sh` 验证 MySQL、Redis、Sentinel 与 Gateway 全链路。Linux1 的独立 DDNS 程序每五分钟同时维护 A/AAAA 记录，只在地址变化或失败时向飞书通知；阿里云密钥仅保存在本机 `0600` 配置文件，不进入仓库或 Jenkins 日志。

阿里云入口没有 IPv6 默认路由，Linux1 的家庭公网 IPv4 又不依赖 9000 端口映射。按 ADR-158 在
Linux1 配置 `wao-cloud-tunnel.service`，主动建立到云机 loopback `127.0.0.1:9000` 的受限 SSH
远程转发；云机专用账号只允许该 listen 地址，并在 `/etc/hosts` 将 `linux1.wayuwa.cn` 指向
`127.0.0.1`。必须同时验证 Linux1 user service 为 `active`、云机仅在 loopback 监听 9000，且从
云机执行 `curl -4 -kfsS https://linux1.wayuwa.cn:9000/api/hello` 返回 `code=0`。不得将转发监听到公网。

## Linux 主节点（娃弟）

当前状态：已完成。公网 Gateway、App、Redis、Sentinel、既有 MySQL 9.7 与 Nacos 均已通过健康检查，云端可经 `linux2.wayuwa.cn:9000` 回源。

1. 确认 `linux2.wayuwa.cn` 可解析且 SSH 公网可达，把专用部署公钥加入 `wadi` 的 `authorized_keys`。
2. 将项目放在 XDG 桌面目录的 `deploy/wao`，复制共享的私有 `deploy/.env`。
3. 当前实机复用已有 MySQL 9.7、Nacos 与 DDNS-Go，在 `.env` 设置 `MANAGE_MASTER_INFRA=false`、`MYSQL_HOST=host.docker.internal`、`NACOS_SERVER_ADDR=host.docker.internal:8848`，并将 `MASTER_BIND_IP` 设为物理网卡地址。
4. 使用宝塔保存的 MySQL 管理凭据在服务器内部创建 WAO 独立库、应用账号与复制账号；在现有 Nacos 创建 WAO 独立账号。不要输出或重置已有管理密码。
5. 生成 Gateway 证书，执行 `sh deploy/scripts/deploy-linux.sh master`。脚本会在需要时自动通过免密 `sudo docker` 运行，不要求重登。
6. 确认现有 DDNS-Go 持续更新 `linux2.wayuwa.cn`，完成路由器端口映射并验证跨节点连通。

Codex 出站由 Compose 的 `codex-vpn` 服务无桌面运行 Clash Verge 已生成的私有 mihomo 配置，
`codex-egress-proxy` 在 WAO bridge 内接收应用请求，再显式上送到 `codex-vpn:7897`。代理不配置 `ports`，
`codex-vpn` 只把 7897 发布到宿主回环供 Linux1 SSH 转发；tinyproxy ACL 只允许
`127.0.0.1` 和 Docker `172.16.0.0/12` 来源；主机防火墙与路由器也不得放行 18888。`wao-server` 通过
Compose 服务名连接代理，不再回连可能被主机防火墙阻断的物理网卡地址。部署后执行
`sh deploy/scripts/probe-codex-egress.sh`，必须由 `wao-server` 经 `WAO_AI_CODEX_PROXY_URL` 请求真实 ChatGPT
额度端点并取得 HTTP 200 或 401。探针会先验证代理容器经无桌面 VPN 服务的真实链路，再验证 `wao-server`
到代理的完整链路，并在失败时输出 tinyproxy 末尾日志。若连接被拒绝或返回其它状态，先检查
`docker compose -f deploy/docker-compose-master.yml ps codex-vpn` 与本机 `7897`，再检查
`docker compose -f deploy/docker-compose-master.yml ps codex-egress-proxy` 及其日志，并核对
`codex-vpn:7897` 上游，不要给 Linux1 从节点或阿里云节点增加直连回退路由。
CD 流水线也会跑同一个探针，但只作为告警：失败时 run 摘要出现 warning 注解，部署本身照常算成功（见 ADR-034）。
所以 Codex 登录报区域错误时，先去 Jenkins 最近一次部署日志查看这条 warning，而不是等流水线变红。
每次主节点发布都会显式构建并强制重建无状态代理，避免复用同一镜像标签时旧容器仍指向上一版镜像。

## 云服务器

当前状态：已完成。`wao.wayuwa.cn` 已签发并自动续期 HTTPS 证书，静态前端已上线，Nginx 已配置 Linux2/Linux1 双 Gateway 上游；云机保持前端-only，不运行 Docker。AI 账号、集中凭据下发和 Runner 领取凭据（包括 `/api/ai/runner/assistant-v2/claim`）固定走具备 VPN 出口的 Linux2 主节点；目标创建、运行编排、心跳与结果回传（包括 `/api/ai/runner/assistant-v2/{runId}/complete`）和普通 API 一样走双节点。旧 `/api/ai/runner/assistant/claim` 不再映射，Nginx 也不保留该旧特例。Linux 的 Codex OAuth 客户端再经 host-network 内部代理复用主机 VPN，阿里云 VM 本身不需要 VPN。CD 按 ADR-050 与 ADR-157 顺序发布，并在应用节点重启前按 ADR-051 从云 Nginx 主动摘流。

1. 把专用部署公钥加入 root 的 `authorized_keys`，将代码放在 `/opt/wao`；云机不复制包含数据库凭据的 `deploy/.env`。
2. 将 `wao.wayuwa.cn` 的 A 记录指向 `8.136.152.131`，安全组放行 22、80、443。
3. 执行 `provision-cloud-node.sh <证书通知邮箱>`，完成 Nginx、证书、健康检查和续期验证。云机不安装 Docker，也不运行数据库或 Sentinel。
4. 构建并上传前端到 `/var/www/wao/dist`。

## CI/CD 开启条件

- Jenkins 已按 `deploy/jenkins/README.md` 安装在 Linux 主节点且 UI 只监听 loopback；
- Linux1、云节点已授权各自的 Jenkins 专用部署公钥；
- 两个 SSH 主机公钥已人工核验并写入 Jenkins `slave-known-hosts`、`cloud-known-hosts`；
- 两个 DDNS 域名、公网端口映射和 `wao.wayuwa.cn` DNS 已生效；
- 主、从节点已拥有同一份私有 `deploy/.env` 和对应 Gateway 证书；
- 共享 `.env` 明确包含 Linux2 master、Linux1 slave 与两台 Sentinel，且不再保留退役节点地址；
- Linux1 到云入口的 loopback 反向 Gateway 通道已启用并通过云端 `/api/hello` 验收；
- GitHub push webhook HMAC 验证和反向 SSH 隧道已通过验收；
- 手工影子构建通过后，再让 `main` push 自动执行生产发布。

## V77 首次发布安全窗口

V77 为飞书按日归档增加稳定身份和全局版本 CAS。旧 `wao-server` 不会签发版本，所以首次应用 V77 时不能使用日常“一个旧节点留在流量中”的滚动顺序。本次上线不创建 MySQL trigger：trigger 的 binlog / 权限、`DEFINER` 和与应用回滚无法事务化协调的风险过高。

发布前确认：

- Linux2 主节点、Linux1 从节点和云 Nginx 必须全部可达。任一节点预检失败时按 ADR-097 保持故障节点
  upstream 为 `down` 并终止发布，不允许以 Linux 单节点完成作为成功结果。
- 云机已安装 `set-nginx-upstream-state.sh`，两个 upstream 状态可写；主库、复制与备份健康。
- 不手工恢复飞书同步 job、不手工启动旧镜像，也不用 Jenkins 手工重跑或新 push 绕过已开始但未完成的窗口。CI 将 `deployment.v77.rollout-state` 作为持久 rollout marker，而不只看当次 push 的文件列表；该发布必须不可被后续 push 取消。

Jenkins `wao-main-cd` 自动顺序：

1. 在路由准备前先上传静止脚本并运行 `rollout-status`。脚本只在 Flyway schema history 已有 V77 成功记录且 `deployment.v77.rollout-state = complete` 时返回完成；任一条件不满足都执行以下安全流程。运行 `deploy/scripts/test-quiesce-wao-server.sh` 确认主、从 Linux 静止脚本的 Compose 参数和 automation SQL 未漂移。
2. 将 master 和 slave 两个云 Nginx upstream 都设为 `down`；用 8 秒有界探测 Linux1 从节点。可达时上传并执行 Linux 静止脚本；不可达时保持 slave 摘流并将发布判为失败。
3. 执行 `pause-feishu-sync`：以 `INSERT IGNORE` 把 `job-feishu-chat-sync` 首次读到的状态保存到 `deployment.v77.feishu-sync-status`，写入 `deployment.v77.rollout-state = in-progress`，再暂停 job。重试不覆盖最初值，因此保留原来就暂停的语义，成功后也不会一律设为启用。
4. 停止 Linux2 旧 `wao-server`，并确认 Linux1 旧服务已经停止；任何节点无法确认时均不得开始 migration。
5. 部署全新节点；第一个新 `wao-server` 启动时由 Flyway 执行 V77。只有运行新二进制且健康检查通过的节点可重新入流；不允许旧节点因发布脚本回滚而入流。
6. 两个节点都完成时执行 `complete-v77-rollout` 并恢复飞书同步；任一节点失败时保留 rollout marker 和同步暂停状态，
   修复后重新执行同一提交，直到双节点完成。

失败处理：

- migration 前失败时不应有任何 schema 变更；修复 SSH、Docker 或 Nginx 问题后，由 `rollout-status` 在同一持久 marker 下继续安全流程。
- migration 已开始后任一新节点部署失败，发布脚本可能先恢复它的旧镜像；维护窗口必须随即再次 stop 该旧容器。在 marker 未完成时 master upstream 保持 `down`，不通过旧二进制回滚 schema。
- 只有确认两个节点全部运行新二进制、`/api/hello` 正常、Flyway 已记录 V77、飞书 job 已恢复原状态、`deployment.v77.rollout-state = complete` 且保存键已删除后，才解除完整维护窗口。如果仅 completion 失败，先保持 job 暂停和 master upstream down；确认新节点后再在 Linux 主节点重试
  `sh deploy/scripts/quiesce-wao-server.sh master complete-v77-rollout`，不单独调用 `restore-feishu-sync`、不猜测原状态或手写 SQL。

## 上线验收

访问 `https://wao.wayuwa.cn/api/hello`，应得到 HTTP 200、`X-Request-Id` 和 `code=0`。前端发布后，Jenkins 会在最多 12 次、每次间隔 5 秒的有界窗口内同时核对版本文件与首页 `data-v` 已收敛到当前提交的前 7 位 SHA；版本长度固定为 7 位，不受 Jenkins 工作区 `core.abbrev` 配置影响。这一窗口只覆盖云节点原子替换后的短暂可见性，不放宽最终版本一致性要求。随后检查 MySQL `SHOW REPLICA STATUS`、两个 Sentinel 的 master 视图、Nacos 的两组实例，以及一次备份恢复演练。双 Sentinel 只提供监控与发现；整机故障下的自动切换要等独立第三仲裁节点加入后再启用。

真实密码、Token、私钥和 DNS 凭据不得写入仓库。SSH 密码只用于首次安装公钥，CI/CD 只使用专用部署密钥。
