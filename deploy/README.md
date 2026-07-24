# WAO 部署说明

## 目录约定

- Linux 主节点：`~/Desktop/deploy/wao`
- Windows 从节点：`D:\deploy\wao`
- 云服务器：应用源码建议 `/opt/wao`，前端制品 `/var/www/wao/dist`

每台机器的 `deploy/.env` 都是私有文件，不提交 Git。以根目录 `.env.example` 为模板，只保留本机角色需要的值。

三台机器加入同一 Tailnet 并取得 IP 后，可在 Windows 管理节点一次性生成共享的强随机配置：

```powershell
.\deploy\scripts\new-deployment-env.ps1 `
  -MasterTailscaleIp <主节点IP> `
  -SlaveTailscaleIp <从节点IP> `
  -CloudTailscaleIp <云节点IP>
```

通过已认证的加密通道把生成的同一份 `deploy/.env` 复制到三台机器，Linux 上执行 `chmod 600 deploy/.env`。脚本不向终端打印任何密钥。

## 部署前置

1. 三台机器安装并登录 Tailscale，记录私网 IP。
2. Linux 主节点安装 Docker Engine；Windows Server 从节点执行 `provision-windows-node.ps1`，在 WSL2 Ubuntu 24.04 中安装 Docker Engine。不要在 Windows Server 安装 Docker Desktop。
3. 防火墙只允许 Tailscale 网段访问 3306、6379、8848、9000、9876、26379。
4. 在主、从节点分别生成 Gateway PKCS12：Linux 执行 `sh deploy/scripts/generate-gateway-cert.sh`，Windows 执行 `deploy\scripts\generate-gateway-cert.ps1`。

Ubuntu 节点可先安装 Tailscale，再完成浏览器授权：

```bash
sudo sh deploy/scripts/install-tailscale-linux.sh
sudo tailscale up
```

Windows 版 Tailscale 请从官方渠道下载 MSI，然后以管理员 PowerShell 校验签名并安装：

```powershell
.\deploy\scripts\install-tailscale-windows.ps1 -MsiPath <下载的MSI路径>
& "$env:ProgramFiles\Tailscale\tailscale.exe" up
```

## 启动

主节点：

```bash
sh deploy/scripts/deploy-linux.sh master
```

从节点（PowerShell）：

```powershell
.\deploy\scripts\deploy-windows.ps1
```

从节点首次准备需要管理员 PowerShell 执行：

```powershell
.\deploy\scripts\provision-windows-node.ps1
```

如果脚本返回 3010，重启 Windows 后再次执行。部署脚本会刷新只监听 Tailscale IPv4 的 WSL 端口转发。

## DDNS

主、从 Compose 都包含固定版本的 DDNS-Go。首次启动后只通过 Tailscale 访问 `http://<节点Tailscale-IP>:9876`：

- Linux 主节点配置 `linux1.wayuwa.cn`。
- Windows 从节点配置 `windows1.wayuwa.cn`。
- DNS 服务商 Token 仅保存在各自的 `ddns-go-data` 数据卷，不写入 `.env`、仓库或 CI。

完成后从公网解析器验证两个记录都指向各自的公网出口地址。

## 云节点

先确认 `wao.wayuwa.cn` 已解析到云服务器、80/443 已放行，并将仓库放在 `/opt/wao`。然后一次性安装 Nginx/Certbot、签发证书、启动云 Sentinel、安装健康检查和验证续期：

```bash
sudo sh deploy/scripts/provision-cloud-node.sh <证书通知邮箱>
```

MySQL 从库只在数据卷首次初始化时自动配置 GTID 复制。验证命令：

```powershell
docker compose --env-file deploy/.env -f deploy/docker-compose-slave.yml exec mysql-slave mysql -uroot -p -e "SHOW REPLICA STATUS\G"
```

## 备份

Windows 任务计划每天执行 `deploy\scripts\backup-mysql.ps1`；每月执行 `deploy\scripts\restore-drill.ps1`。备份保存在 `deploy\backups`，默认保留 7 天。

## Nginx 与证书

云服务器写好 `deploy/.env` 后，推荐使用上一节的 `provision-cloud-node.sh` 完成首次初始化。它先启用仅支持 ACME 的 HTTP 配置，证书签发成功后再切换到 HTTPS 生产配置，避免证书尚不存在时 `nginx -t` 失败。

后续只需在模板变更时重新渲染：

```bash
sh deploy/scripts/render-nginx-config.sh
sudo install -m 0644 deploy/nginx/wao.wayuwa.cn.conf /etc/nginx/conf.d/wao.wayuwa.cn.conf
sudo nginx -t && sudo systemctl reload nginx
```

前端生产制品同步到 `/var/www/wao/dist`，然后执行 `nginx -t && systemctl reload nginx`。

## GitHub Secrets

启用 CD 前配置：`DEPLOY_SSH_PRIVATE_KEY`、`DEPLOY_SSH_KNOWN_HOSTS`、`MASTER_HOST`、`MASTER_USER`、`SLAVE_HOST`、`SLAVE_USER`、`CLOUD_HOST`、`CLOUD_USER`，并将仓库变量 `DEPLOY_ENABLED` 设为 `true`。

- `MASTER_HOST`、`SLAVE_HOST` 使用 Tailscale IPv4。
- `CLOUD_HOST` 使用云服务器固定公网地址。
- `DEPLOY_SSH_KNOWN_HOSTS` 固定保存三个目标的 SSH 主机公钥。
- Actions 只连接云服务器公网 SSH，再通过 `ProxyJump` 访问主、从节点。

主节点发布到 `~/Desktop/deploy/wao`，从节点发布到 `D:\deploy\wao`，云服务器前端发布到 `/var/www/wao/dist`。密码登录仅用于首次安装公钥，流水线不保存 SSH 密码。
