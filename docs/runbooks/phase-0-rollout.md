# Phase 0 实机上线清单

## 已完成

- Java 17 多模块工程、统一响应、异常处理、Hello API 与测试。
- Spring Cloud Gateway 路由、请求 ID、Nacos 2.x 服务发现配置与测试。
- Vue 3 前端、Pinia 状态、Ant Design Vue 页面及单元测试。
- Linux 主节点、Windows/WSL2 从节点、云 Sentinel 的 Docker Compose。
- MySQL GTID 主从、Redis 主从与三 Sentinel、DDNS-Go、Gateway TLS、备份恢复脚本。
- GitHub Actions 构建、测试、容器构建与基于 SSH 密钥的三节点发布。
- 本机经 Vite -> Gateway -> wao-server 调用 `GET /api/hello` 验证 HTTP 200。

## Windows 从节点（娃哥）

当前机器已经启用 WSL 可选功能，Windows 必须重启一次才能继续。重启后，以管理员 PowerShell 在仓库根目录执行：

```powershell
.\deploy\scripts\provision-windows-node.ps1
```

脚本会把 Ubuntu 24.04 安装到 `D:\WSL\Ubuntu-24.04`，并在 WSL 内安装 Docker Engine。随后：

1. 从 Tailscale 官方渠道下载 MSI，执行 `install-tailscale-windows.ps1` 并完成交互登录。
2. 根据根目录 `.env.example` 创建 `deploy\.env`，使用新生成的强随机凭据。
3. 执行 `generate-gateway-cert.ps1`，再执行 `deploy-windows.ps1`。
4. 通过 Tailscale 打开 DDNS-Go 的 9876 端口，配置 `windows1.wayuwa.cn`。

## Linux 主节点（娃弟）

1. 安装并登录 Tailscale，安装 Docker Engine 与 Compose 插件。
2. 将项目放在桌面 `deploy/wao`，创建私有的 `deploy/.env`。
3. 生成 Gateway 证书，执行 `sh deploy/scripts/deploy-linux.sh master`。
4. 通过 Tailscale 打开 DDNS-Go 的 9876 端口，配置 `linux1.wayuwa.cn`。

## 云服务器

1. 执行 `install-tailscale-linux.sh` 并完成登录，安装 Docker Engine。
2. 创建私有的 `deploy/.env`，启动 `cloud-sentinel`。
3. 将 `wao.wayuwa.cn` 的公网 DNS 记录指向云服务器。
4. 执行 `provision-cloud-node.sh <证书通知邮箱>`，完成 Nginx、证书、云 Sentinel、健康检查和续期验证。
5. 构建并上传前端到 `/var/www/wao/dist`。

## 上线验收

```text
https://wao.wayuwa.cn/api/hello
```

应返回 HTTP 200、`X-Request-Id` 响应头和 `code=0` 的统一 JSON。随后检查 MySQL `SHOW REPLICA STATUS`、三个 Sentinel 的 master 视图、两台应用实例的 Nacos 注册状态，以及一次备份恢复演练。

真实密码、Token、私钥和 DNS 服务商凭据不得写入仓库。SSH 密码只用于首次安装公钥，CI/CD 只使用专用部署密钥。
