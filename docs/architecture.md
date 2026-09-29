# WAO 架构基线

## 当前拓扑

自 [ADR-291](adr/291-linux2-only-deployment-and-wao-recovery.md) 采用后，Linux2 是唯一 Linux 部署节点。云服务器提供 HTTPS、Nginx、浏览器静态制品和异地加密备份。当前应用没有第二个 Linux 节点提供故障转移。

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| Vue 前端 | 云服务器 | Nginx 托管不可变静态制品 |
| WAO Server、Agent、Gateway | Linux2 | Docker Compose 本机部署 |
| MySQL | Linux2 | WAO 写主和应用同节点 |
| Redis | Linux2 | 应用容器直连本机服务，当前无 Sentinel 自动切换 |
| Nacos | Linux2 | 服务注册与基础配置 |
| Nginx/TLS | 云服务器 | 静态入口、API 反向代理和公开就绪检查 |
| Jenkins | Linux2 | 主分支批处理构建与发布 |
| JumpServer | Linux2 与云服务器 | 只纳管当前两个维护目标 |

## 请求链路

浏览器 → 云服务器 Nginx / 静态文件 → Linux2 Gateway → 同机 WAO Server → Linux2 MySQL 与 Redis。

Jenkins 在 Linux2 构建，并将网页静态制品发布到云端；后端制品在 Linux2 本机更新。

## 发布和可用性

- 纯浏览器变更只切换云端静态制品；后端版本保持不变。
- 后端变更原地更新 Linux2，发布窗口内可能短暂中断。
- 云 Nginx 只有一个 WAO Gateway 上游；失败时保留最后一个有效配置，不将流量切换到离线主机。
- Jenkins 成功状态以 Linux2 Gateway、云端静态资源和公共 /api/edge/ready 通过为条件。
- 监控目标为公共入口和 Linux2；JumpServer 当前只授权 Linux2 与云入口。

## 网络与凭据边界

- 私有值来自 Linux2 私有 deploy/.env、deploy/.env.secrets、Jenkins Secret 文件或系统凭据存储。
- 云端只保存其运行所需的 Nginx、证书及加密备份材料；应用数据库继续在 Linux2。
- 对象存储 Garage 的三节点布局受 Linux1 退役影响，必须独立执行数据布局迁移和恢复验证；在该决策完成前不能把旧三节点示例当作当前运行拓扑。
- 备份恢复依据 [Linux2 单节点异地备份手册](runbooks/linux2-only-cloud-backup.md) 与 [ADR-291](adr/291-linux2-only-deployment-and-wao-recovery.md)。

## 兼容性基线

Spring Boot 3.2.4、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0、Nacos 2.4.3。升级需新增 ADR 并完成兼容性验证。
