# WAO 架构基线

## 目标

Phase 0 建立一条最小但真实的纵向链路：Vue 页面请求 `/api/hello`，开发环境由 Vite 代理到 `:9000`，生产环境由云 Nginx 代理到双节点 Gateway；Gateway 通过 Nacos 发现 `wao-server` 并返回统一 JSON。

## 组件与端口

| 组件           |      端口 | 部署位置   |
| -------------- | --------: | ---------- |
| Vue 开发服务器 |      5173 | 开发机     |
| WAO Server     |      8080 | 主、从节点 |
| WAO Gateway    |      9000 | 主、从节点 |
| Nacos 2.4.3    | 8848/9848 | 主节点     |
| MySQL 9        |      3306 | 主、从节点 |
| Redis 7        |      6379 | 主、从节点 |
| Redis Sentinel |     26379 | 主、从节点 |
| DDNS-Go        |      9876 | 主、从节点 |
| Nginx          |    80/443 | 云服务器   |

## 请求链路

```mermaid
flowchart LR
    Browser["浏览器"] --> Nginx["云 Nginx / 静态文件"]
    Nginx -->|"HTTPS /api/*"| Gateway["双节点 Gateway"]
    Gateway -->|"Nacos + lb://wao-server"| Server["双节点 WAO Server"]
    Server --> MySQL[("MySQL 主库")]
    Server --> Redis[("Redis Sentinel")]
```

## 环境策略

- `dev`：使用本机 Docker 基础设施；Swagger 开启；Nacos 可关闭鉴权。
- `prod`：敏感值来自环境变量；Swagger 关闭；跨节点通过公网地址和 DDNS 域名通信。
- 前端生产制品直接由云 Nginx 托管，不运行 Node.js 进程。
- Windows Server 2025 不安装 Docker Desktop；从节点容器运行在 WSL2 Ubuntu 24.04 的 Docker Engine 中，Windows `portproxy` 将公网端口转发到 WSL。
- 主、从节点检测公网 IPv4 变化并刷新 Nacos 注册；云 Nginx 定期重载以重新解析 DDNS 上游。

## 版本决策

方案要求 Spring Boot 3 与 Nacos 2.x。Phase 0 使用 Spring Boot 3.2.4、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0 和 Nacos 2.4.3。升级必须先新增 ADR 并完成兼容性验证。

## 网络与凭据边界

- Git 只保存 `.env.example`；真实值进入各服务器私有 `deploy/.env` 或 GitHub Secrets。
- 根据 ADR-005，当前接受 SSH、Gateway、MySQL、Redis、Nacos、Sentinel 与 DDNS-Go 按路由器/安全组规则公网可达，不使用覆盖网络。
- 用户入口只开放 `https://wao.wayuwa.cn`；Gateway 回源使用 TLS，SSH 使用专用部署密钥与固定主机密钥校验。
- Redis 副本和两个 Sentinel 宣告公网可解析主机名，避免跨主机发现到容器内网地址。双 Sentinel 不承诺整机故障下的自动切换；可靠自动故障转移需要新增独立第三仲裁节点。
- 正式生产前必须重新评估端口白名单、数据库链路加密、SSH 防爆破和私网/VPN。
