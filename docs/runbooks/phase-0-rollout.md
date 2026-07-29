# Phase 0 实机上线清单

## 已完成

- Java 17 多模块、Gateway、Nacos、Vue 3 前端与 Hello API 纵向切片。
- Linux 主节点、Windows/WSL2 从节点及云前端节点的部署与运维脚本。
- MySQL GTID 主从、Redis 主从与双 Sentinel、DDNS-Go、Gateway TLS、Nginx、备份恢复。
- GitHub Actions 测试、容器构建、三节点 SSH 发布和飞书结果通知。
- 本机 Vite -> Gateway -> WAO Server 的 `GET /api/hello` 验证。

## Windows 从节点（娃哥）

当前状态：已完成。WSL 2.7.10、Ubuntu 24.04、Docker Engine/Compose、六个从节点容器、Windows `portproxy` 和开机保活任务均已运行；公网 Gateway 及数据复制已通过验收。WSL 位于 `D:\WSL\WAO-Ubuntu`，限制为 4 核/8GB。

在 `wage` 的管理员 PowerShell/SSH 会话中可重复执行：

```powershell
Set-Location D:\deploy\wao
.\deploy\scripts\provision-windows-node.ps1
.\deploy\scripts\deploy-windows.ps1 -SkipImageBuild
```

Windows 发布包使用 ZIP 而不是 tar.gz，确保中文技术方案文件名可在 Windows 上无损解压；节点使用已预热的基础镜像本地构建，避免 GitHub Runner 跨境传输大镜像。MySQL GTID 从库只复制 `wao.%`，复制双线程为 `Yes`、延迟为 0。

共享 Gateway 证书可在重启前用 Windows JDK 执行 `generate-gateway-cert.ps1` 生成。

使用 `sh deploy/scripts/validate-slave.sh` 验证 MySQL、Redis、Sentinel 与 Gateway 全链路。DDNS-Go 的 DNS 服务商 Token 仅保存在本机数据卷；如尚未配置，需要在 `:9876` 管理页完成一次性设置。

## Linux 主节点（娃弟）

当前状态：已完成。公网 Gateway、App、Redis、Sentinel、既有 MySQL 9.7 与 Nacos 均已通过健康检查，云端可经 `linux2.wayuwa.cn:9000` 回源。

1. 确认 `linux2.wayuwa.cn` 可解析且 SSH 公网可达，把专用部署公钥加入 `wadi` 的 `authorized_keys`。
2. 将项目放在 XDG 桌面目录的 `deploy/wao`，复制共享的私有 `deploy/.env`。
3. 当前实机复用已有 MySQL 9.7、Nacos 与 DDNS-Go，在 `.env` 设置 `MANAGE_MASTER_INFRA=false`、`MYSQL_HOST=host.docker.internal`、`NACOS_SERVER_ADDR=host.docker.internal:8848`，并将 `MASTER_BIND_IP` 设为物理网卡地址。
4. 使用宝塔保存的 MySQL 管理凭据在服务器内部创建 WAO 独立库、应用账号与复制账号；在现有 Nacos 创建 WAO 独立账号。不要输出或重置已有管理密码。
5. 生成 Gateway 证书，执行 `sh deploy/scripts/deploy-linux.sh master`。脚本会在需要时自动通过免密 `sudo docker` 运行，不要求重登。
6. 确认现有 DDNS-Go 持续更新 `linux2.wayuwa.cn`，完成路由器端口映射并验证跨节点连通。

## 云服务器

当前状态：已完成。`wao.wayuwa.cn` 已签发并自动续期 HTTPS 证书，静态前端已上线，Nginx 已配置 Linux/Windows 双 Gateway 上游；云机保持前端-only，不运行 Docker。普通 API 在双上游间分流，AI 账号与 AI Runner API 按 ADR-034 固定走具备 VPN 出口的 Linux 主节点；阿里云 VM 本身不需要 VPN。

1. 把专用部署公钥加入 root 的 `authorized_keys`，将代码放在 `/opt/wao`；云机不复制包含数据库凭据的 `deploy/.env`。
2. 将 `wao.wayuwa.cn` 的 A 记录指向 `8.136.152.131`，安全组放行 22、80、443。
3. 执行 `provision-cloud-node.sh <证书通知邮箱>`，完成 Nginx、证书、健康检查和续期验证。云机不安装 Docker，也不运行数据库或 Sentinel。
4. 构建并上传前端到 `/var/www/wao/dist`。

## CI/CD 开启条件

- 三个公网 SSH 目标均已授权专用部署公钥；
- 三个 SSH 主机公钥已人工核验并写入 `DEPLOY_SSH_KNOWN_HOSTS`；
- 两个 DDNS 域名、公网端口映射和 `wao.wayuwa.cn` DNS 已生效；
- 主、从节点已拥有同一份私有 `deploy/.env` 和对应 Gateway 证书；
- 手工首发与数据复制验收通过后，再将 `DEPLOY_ENABLED` 设为 `true`。

## 上线验收

访问 `https://wao.wayuwa.cn/api/hello`，应得到 HTTP 200、`X-Request-Id` 和 `code=0`。随后检查 MySQL `SHOW REPLICA STATUS`、两个 Sentinel 的 master 视图、Nacos 的两组实例，以及一次备份恢复演练。双 Sentinel 只提供监控与发现；整机故障下的自动切换要等独立第三仲裁节点加入后再启用。

真实密码、Token、私钥和 DNS 凭据不得写入仓库。SSH 密码只用于首次安装公钥，CI/CD 只使用专用部署密钥。
