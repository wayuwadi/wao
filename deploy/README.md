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
|       8080 |  可选  |  可选  | App 直连诊断；Gateway 不依赖公网 8080 |
|       9000 |   是   |   是   | Gateway / 云 Nginx 上游 |
|       9876 |  可选  |  可选  | DDNS-Go 管理            |
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

Windows Server 不支持本项目所需的 Docker Desktop 运行方式，因此从节点在 WSL2 Ubuntu 24.04 的 Docker Engine 中运行 Linux 容器。首次准备需要以 CI/CD 使用的 `wage` 管理员账号执行 PowerShell，确保 WSL 发行版属于同一 Windows 用户：

```powershell
.\deploy\scripts\provision-windows-node.ps1
```

如果返回 3010，重启 Windows 后再次执行。然后执行：

```powershell
.\deploy\scripts\deploy-windows.ps1
```

CI/CD 向 Windows 发送 `D:\deploy\wao-release.zip`，由 PowerShell `Expand-Archive` 解压，以完整保留原版技术方案等中文文件名。MySQL 从库只复制 `wao.%`，不会同步 Linux 主机上已有的其他业务库。

准备脚本在没有现有配置时将 WSL 限制为 4 核/8GB/2GB swap，并创建 `WAO WSL Keepalive` 开机计划任务。部署脚本会把公开端口从 Windows `0.0.0.0` 转发到 WSL 当前地址；每次发布都会刷新 WSL 动态 IP 对应的 `portproxy`。

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

MySQL 从库只在数据卷首次初始化时自动配置 GTID 复制。MySQL 9 的复制密码最多 32 个字符，`new-deployment-env.ps1` 已为该字段生成 32 位十六进制随机值。验证：

```powershell
wsl.exe -d Ubuntu -u root -- sh /mnt/d/deploy/wao/deploy/scripts/validate-slave.sh
```

如果主库已经清理了从库所需的早期 binlog，先用 `mysqldump --single-transaction --set-gtid-purged=ON` 生成当前 `wao` 快照，再执行 `bootstrap-mysql-slave.sh <快照.sql.gz>`。该脚本只重置从库 GTID，不修改主库业务数据。

Windows 任务计划每天执行 `deploy\scripts\backup-mysql.ps1`，每月执行 `deploy\scripts\restore-drill.ps1`。备份位于 `deploy\backups`，默认保留 7 天。

最终入口应为：

```text
https://wao.wayuwa.cn/api/hello
```

应返回 HTTP 200、`X-Request-Id` 响应头和 `code=0` 的统一 JSON；随后检查 MySQL 复制、两个 Sentinel 的 master 视图和 Nacos 中的两台应用实例。

当前只有主、从两个 Sentinel。它们可提供监控和客户端发现，但任一整机离线后无法形成可靠多数派，因此不承诺整机故障下的 Redis 自动切换。后续如需自动故障转移，应增加独立的第三仲裁节点。

## 图床

用户上传的图片（任务 / 子任务描述里的截图）存在主节点 MySQL 的 `image_blob` 表，不落容器本地目录——主从两个节点的 `wao-server` 都连主节点的 MySQL，所以落库天然满足「图床在主节点上」，容器重建与节点故障转移都不丢图，也不需要额外的 volume 或 Nginx 定向路由。理由见 `docs/adr/012-markdown-rendering-and-image-store.md`。

单张上限 **10MB**，这个数字在三处必须保持一致，改一处就要同步另外两处：

| 位置 | 配置 |
| --- | --- |
| 应用 | `ImageService.MAX_BYTES` / `spring.servlet.multipart.max-file-size` |
| 网关 | `spring.codec.max-in-memory-size: 16MB` |
| 云 Nginx | `location /api/` 的 `client_max_body_size 12m`（留 2MB 给 multipart 边界） |

云节点更新 Nginx 配置后记得 `nginx -t && systemctl reload nginx`，否则上传大图会返回 413。

图片正文进了 MySQL，`backup-mysql.ps1` 的备份体积会随图片增长，留意 `deploy\backups` 的磁盘占用。

## 任务分享链接

任务分享页 `/s/t/<taskId>` 与 Markdown 直读地址 `/api/share/tasks/<taskId>.md` 都是**公开免登录**的：拿到链接就能读到该任务的标题、描述、图片与子任务（负责人、评论、活动不外泄）。当前任务 id 是可枚举的时间戳，因此这套分享**不适合在有外部成员的正式环境启用**，收敛方案（不可猜的 share token）记在 `docs/adr/013-public-task-share-links.md`。

## GitHub Actions

CD 使用以下 GitHub Secrets：`DEPLOY_SSH_PRIVATE_KEY`、`DEPLOY_SSH_KNOWN_HOSTS`、`MASTER_HOST`、`MASTER_USER`、`SLAVE_HOST`、`SLAVE_USER`、`CLOUD_HOST`、`CLOUD_USER`、`FEISHU_WEBHOOK_URL`、`FEISHU_APP_ID`、`FEISHU_APP_SECRET`，并用仓库变量 `DEPLOY_ENABLED` 控制是否部署。开发期 CD 会构建一次前端并行发布三台节点，决策见 [ADR-011](../docs/adr/011-development-fast-delivery.md)。

## 飞书登录

将飞书 App ID 与 App Secret 配置为同名 GitHub Actions Secrets。CD 会把它们与主节点现有的 `WAO_VAULT_KEY` 合并下发到主节点和从节点私有的 `deploy/.env.secrets`，不要提交 Git 或写入 GitHub Actions 日志：

```dotenv
FEISHU_LOGIN_ENABLED=true
FEISHU_APP_ID=<飞书 App ID>
FEISHU_APP_SECRET=<飞书 App Secret>
FEISHU_REDIRECT_URI=https://wao.wayuwa.cn/api/auth/feishu/callback
FEISHU_FRONTEND_URL=https://wao.wayuwa.cn
```

在飞书开发者后台的安全设置中添加同一个重定向 URL。网页应用的工作台在线网页主页配置为 `https://wao.wayuwa.cn/api/auth/feishu/workbench`；PC 端“跳转至浏览器打开新页面”配置为 `https://wao.wayuwa.cn/api/auth/feishu/browser`。修改 Secret 后手动运行一次 `WAO Fast CD`，或随下一次 `main` push 自动下发并重建 `wao-server` 容器。

## 密钥库加密密钥

`WAO_VAULT_KEY` 是密钥库密码字段的 AES-256-GCM 加密密钥（base64 编码的 32 字节），主、从两个节点必须使用**同一个值**（`docs/adr/009-vault-server-side-encryption.md`）。缺失时 `wao-server` 会拒绝启动（`VaultCryptoService` 主动校验密钥长度），CD 会自动回滚到上一个能启动的镜像，不会造成线上不可用，但会一直部署失败直到补上这个变量：

```bash
# 生成一次，写进两个节点共享的同一份 deploy/.env（不要提交 Git）
openssl rand -base64 32
```

```dotenv
WAO_VAULT_KEY=<上面生成的值>
```

写入后在各节点重新执行发布脚本重建 `wao-server` 容器；新装机场景下 `deploy/scripts/new-deployment-env.ps1` 已经会自动生成这个值，不需要手动补。

- `MASTER_HOST=linux2.wayuwa.cn`，`SLAVE_HOST=windows1.wayuwa.cn`，云节点使用固定公网地址；
- Actions 从 GitHub 托管 Runner 直接 SSH 到三个公网目标，不使用跳板机；
- Actions 在 Runner 上使用官方软件源执行容器构建检查，但 CD 只下发约百 KB 的源码发布包；两台家庭节点使用已预热的 Maven/Temurin 基础镜像和阿里云 Maven/Alpine 镜像本地分层构建，避免跨境传输百 MB 镜像制品；
- `DEPLOY_SSH_KNOWN_HOSTS` 必须保存人工核验后的三个 SSH 主机公钥；
- 密码登录仅用于首次安装公钥，流水线只使用专用部署密钥；
- 飞书 Webhook 只保存在 GitHub Secret。所有分支 push 和 Pull Request 都通知 CI 结果，开启 CD 后通知还包含部署结果。
