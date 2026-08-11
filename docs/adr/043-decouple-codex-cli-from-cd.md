# ADR-043：Codex CLI 脱离 WAO CD 生命周期

## 状态

已采纳。取代 ADR-041 中“CD 下载并随发布安装 Codex CLI”的决策，其余决策保持不变。

## 背景

WAO CD 曾在每次业务发布时下载约 119 MB 的 Linux Codex CLI 包，再从 GitHub Actions
构建机上传到主节点。该跨网络传输在连接不稳定时多次中断，使原本数分钟的业务发布延长到一小时。
Codex CLI 已由主节点管理员独立维护，无需继续与 WAO 业务版本绑定。

## 决策

- GitHub Actions 不再下载、校验或上传 Codex CLI 独立包。
- `deploy-linux.sh` 不再安装、升级或重启 Codex CLI 及 WAO Runner。
- Codex CLI、Runner 的 systemd 配置、版本升级和凭据状态作为主机级运维依赖，由管理员独立处理。
- CD 保留 Codex 出站代理容器和非阻断健康探针，因为它们仍属于 WAO 服务的运行时网络路由。

## 后果

- 常规发布不再传输 119 MB Codex 包，部署耗时恢复由业务产物构建和节点健康检查决定。
- WAO 发布不会覆盖管理员手工维护的 Codex 版本或重启正在执行任务的 Runner。
- 如主节点未安装 Codex 或 Runner 未上报 `codex` 能力，AI 任务和页面问答会显示节点不可用；业务 CD 不会自动修复主机依赖。
