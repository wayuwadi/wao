# Phase 0 实机上线清单

## 已完成

- Java 17 多模块、Gateway、Nacos、Vue 3 前端与 Hello API 纵向切片。
- Linux 主节点、Windows/WSL2 从节点及云前端节点的部署与运维脚本。
- MySQL GTID 主从、Redis 主从与双 Sentinel、DDNS-Go、Gateway TLS、Nginx、备份恢复。
- GitHub Actions 测试、容器构建、三节点 SSH 发布和飞书结果通知。
- 本机 Vite -> Gateway -> WAO Server 的 `GET /api/hello` 验证。

## Windows 从节点（娃哥）

当前机器已经启用 WSL 可选功能，必须重启一次才能继续安装 Ubuntu 24.04。重启后以管理员 PowerShell 执行：

```powershell
Set-Location D:\CodeSpace\wao
.\deploy\scripts\provision-windows-node.ps1
.\deploy\scripts\deploy-windows.ps1
```

共享 Gateway 证书可在重启前用 Windows JDK 执行 `generate-gateway-cert.ps1` 生成。

随后配置 DDNS-Go 更新 `windows1.wayuwa.cn`，配置路由器端口映射，并为 `refresh-public-ip.ps1` 建立每五分钟运行的任务计划。

## Linux 主节点（娃弟）

1. 确认 `linux2.wayuwa.cn` 可解析且 SSH 公网可达，把专用部署公钥加入 `wadi` 的 `authorized_keys`。
2. 将项目放在桌面 `deploy/wao`，复制共享的私有 `deploy/.env`。
3. 安装 Docker Engine，重新登录使 `docker` 组生效，生成 Gateway 证书，执行 `sh deploy/scripts/deploy-linux.sh master`。
4. 配置 DDNS-Go 更新 `linux2.wayuwa.cn`，完成路由器端口映射并验证跨节点连通。

## 云服务器

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
