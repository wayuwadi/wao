# WAO 部署说明

当前采用公网 + DDNS 三节点直连，不依赖 Tailscale。原版方案保持归档，实际网络决策以 [ADR-005](../docs/adr/005-public-ddns-network.md) 为准。

## 节点与目录

| 节点                   | 公网地址             | 目录                                 |
| ---------------------- | -------------------- | ------------------------------------ |
| Linux 主节点（娃弟）   | `linux2.wayuwa.cn`   | `$(xdg-user-dir DESKTOP)/deploy/wao` |
| Windows 从节点（娃哥） | `windows1.wayuwa.cn` | `D:\deploy\wao`                      |
| 云节点                 | `8.136.152.131`      | `/opt/wao`；前端 `/var/www/wao/dist` |

每台机器的 `deploy/.env` 都是私有文件，不提交 Git。在 Windows 管理节点生成一份共享的强随机配置：

```powershell
.\deploy\scripts\new-deployment-env.ps1
```

通过已经校验主机密钥的 SSH/SCP 把同一份 `deploy/.env` 复制到主、从两个家庭节点，Linux 上执行 `chmod 600 deploy/.env`。生成脚本不向终端打印密钥。云节点只托管前端和 Nginx，不保存数据库或缓存凭据。

## 公网与路由器前置

先确认两个家庭出口不是运营商 CGNAT，并将域名分别解析到对应公网 IPv4：

- `linux2.wayuwa.cn` -> Linux 主节点所在出口；
- `windows1.wayuwa.cn` -> Windows 从节点所在出口；
- `wao.wayuwa.cn` -> `8.136.152.131`。

家庭路由器需要 TCP 端口映射：

|       端口 | 主节点 | 从节点 | 用途                    |
| ---------: | :----: | :----: | ----------------------- |
|         22 |   是   |   是   | SSH / CI/CD             |
|       3306 |   是   |   是   | MySQL 主从与运维        |
|       6379 |   是   |   是   | Redis 主从              |
|      26379 |   是   |   是   | Redis Sentinel          |
|       8080 |   是   |   是   | Nacos 服务实例发现      |
|       9000 |   是   |   是   | Gateway / 云 Nginx 上游 |
|       9876 |   是   |   是   | DDNS-Go 管理            |
| 8848、9848 |   是   |   否   | Nacos HTTP 与 gRPC      |

云安全组只需放行 22、80 和 443。公网暴露家庭节点数据服务的风险已由项目所有者明确接受；仍必须使用生成脚本产生的强随机密码，不得使用示例值。

## 节点初始化与启动

Linux 主节点安装 Docker Engine 与 Compose 插件：

```bash
sudo sh deploy/scripts/install-docker-engine.sh
sh deploy/scripts/generate-gateway-cert.sh
sh deploy/scripts/deploy-linux.sh master
```

当前娃弟主机已经运行 MySQL 9.7、Nacos 与 DDNS-Go，不应启动重复实例。该节点的私有 `.env` 使用：

```dotenv
MANAGE_MASTER_INFRA=false
MYSQL_HOST=host.docker.internal
NACOS_SERVER_ADDR=host.docker.internal:8848
MASTER_BIND_IP=192.168.31.180
```

这种模式下 Compose 只管理 WAO 自己的 Redis、Sentinel、App 和 Gateway。没有既有基础设施的新主机才设置 `MANAGE_MASTER_INFRA=true`，由 `managed-infra` profile 一并启动 MySQL、Nacos 与 DDNS-Go。

Gateway 以非 root 用户运行。部署脚本会将加密的 `gateway.p12` 调整为 `0644` 以便容器读取；私钥仍由私有 `.env` 中的强随机 `GATEWAY_SSL_PASS` 保护，`.env` 本身保持 `0600`。

安装脚本会把当前登录用户加入 `docker` 组；重新登录后再执行部署。主节点部署脚本会为当前用户安装每五分钟运行的公网 IP 刷新任务。

Gateway 证书可以在重启前直接使用 Windows 已安装的 JDK 生成：

```powershell
.\deploy\scripts\generate-gateway-cert.ps1
```

Windows Server 不支持本项目所需的 Docker Desktop 运行方式，因此从节点在 WSL2 Ubuntu 24.04 的 Docker Engine 中运行 Linux 容器。首次准备需要管理员 PowerShell：

```powershell
.\deploy\scripts\provision-windows-node.ps1
```

如果返回 3010，重启 Windows 后再次执行。然后执行：

```powershell
.\deploy\scripts\deploy-windows.ps1
```

部署脚本会把公开端口从 Windows `0.0.0.0` 转发到 WSL 当前地址。WSL 重启后应再次执行部署脚本，或单独执行 `refresh-wsl-portproxy.ps1`。

## DDNS 与公网 IP 变化

主、从 Compose 都包含固定版本的 DDNS-Go。首次启动后访问对应节点的 `http://<公网域名>:9876`：

- Linux 主节点配置 `linux2.wayuwa.cn`；
- Windows 从节点配置 `windows1.wayuwa.cn`；
- DNS 服务商 Token 只保存在各自 `ddns-go-data` 数据卷，不写入 `.env`、Git 或 CI。

Linux 主节点使用 `refresh-public-ip.sh master`；Windows 从节点使用 `refresh-public-ip.ps1`。公网地址变化时脚本会刷新 Nacos 中的实例注册地址。Windows 可在任务计划程序中让登录用户每五分钟运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\deploy\wao\deploy\scripts\refresh-public-ip.ps1
```

## 云节点

先确认 `wao.wayuwa.cn` 已解析到云服务器且 80/443 可访问。将代码放在 `/opt/wao` 后执行：

```bash
sudo sh deploy/scripts/provision-cloud-node.sh <证书通知邮箱>
```

脚本只安装 Nginx 与 Certbot，签发 TLS 证书，安装健康检查与 Nginx DDNS 激活任务，并验证证书续期。云节点不安装 Docker、不运行数据库或 Sentinel；前端生产制品放在 `/var/www/wao/dist`。证书签发前的 HTTP 引导配置会提供静态前端；证书签发后，如果两个 Gateway 健康端点均未就绪，则使用 HTTPS 待后端模式并让 `/api/` 返回 503。定时任务检测到至少一个 Gateway 健康端点可用后，会自动切换到正式双 Gateway 配置。

## 数据、备份与验收

MySQL 从库只在数据卷首次初始化时自动配置 GTID 复制。验证：

```powershell
docker compose --env-file deploy/.env -f deploy/docker-compose-slave.yml exec mysql-slave mysql -uroot -p -e "SHOW REPLICA STATUS\G"
```

Windows 任务计划每天执行 `deploy\scripts\backup-mysql.ps1`，每月执行 `deploy\scripts\restore-drill.ps1`。备份位于 `deploy\backups`，默认保留 7 天。

最终入口应为：

```text
https://wao.wayuwa.cn/api/hello
```

应返回 HTTP 200、`X-Request-Id` 响应头和 `code=0` 的统一 JSON；随后检查 MySQL 复制、两个 Sentinel 的 master 视图和 Nacos 中的两台应用实例。

当前只有主、从两个 Sentinel。它们可提供监控和客户端发现，但任一整机离线后无法形成可靠多数派，因此不承诺整机故障下的 Redis 自动切换。后续如需自动故障转移，应增加独立的第三仲裁节点。

## GitHub Actions

CD 使用以下 GitHub Secrets：`DEPLOY_SSH_PRIVATE_KEY`、`DEPLOY_SSH_KNOWN_HOSTS`、`MASTER_HOST`、`MASTER_USER`、`SLAVE_HOST`、`SLAVE_USER`、`CLOUD_HOST`、`CLOUD_USER`、`FEISHU_WEBHOOK_URL`，并用仓库变量 `DEPLOY_ENABLED` 控制是否部署。

- `MASTER_HOST=linux2.wayuwa.cn`，`SLAVE_HOST=windows1.wayuwa.cn`，云节点使用固定公网地址；
- Actions 从 GitHub 托管 Runner 直接 SSH 到三个公网目标，不使用跳板机；
- `DEPLOY_SSH_KNOWN_HOSTS` 必须保存人工核验后的三个 SSH 主机公钥；
- 密码登录仅用于首次安装公钥，流水线只使用专用部署密钥；
- 飞书 Webhook 只保存在 GitHub Secret。所有分支 push 和 Pull Request 都通知 CI 结果，开启 CD 后通知还包含部署结果。
