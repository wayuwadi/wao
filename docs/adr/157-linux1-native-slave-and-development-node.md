# ADR-157：Linux1 作为原生 Linux 从节点与开发执行机

## 状态

已接受（2026-08-28）。本 ADR 取代当前部署文档中 Windows1/WSL2 从节点的运行方式；描述原方案的历史 ADR 与原版技术方案继续保留，不回写历史事实。

## 上下文

原 Windows1 物理机已重装为 Ubuntu，并启用新域名 `linux1.wayuwa.cn`。Linux2 上的 MySQL 9.7、Nacos、WAO 主节点和 Jenkins 控制器继续稳定运行，因此无需交换主从角色。Linux1 还会作为长期开发机、Codex/其他 AI CLI 执行机和 WAO AI Runner 节点。

旧发布链依赖 Windows OpenSSH、PowerShell、WSL2、`D:\deploy`、ZIP、端口代理与 RDP。继续保留这些假设会让同一台原生 Linux 主机承担无意义的兼容层，并使 CI/CD、JumpServer 和故障提示与真实环境不一致。

## 决策

- Linux2 保持 MySQL/Redis 主节点、Nacos 节点和 Jenkins 控制器；Linux1 作为 MySQL GTID 从库、Redis replica、Sentinel 和第二套 Gateway/App。
- Linux1 的生产目录固定为 `~/deploy/wao`，开发克隆固定为 `~/workspace/wao`。生产发布只使用 Jenkins 构建的不可变 Git SHA 镜像，不从开发工作区直接上线。
- Jenkins 使用 `wao-slave-ssh` 与 `wao-slave-known-hosts`，通过原生 SSH/SCP 下发 tar.gz 发布包、镜像归档、私有配置和证书。远端 `install-release-linux.sh` 使用部署锁安装并验证。
- 滚动发布顺序保持“从节点 → 主节点 → 云前端”，即 Linux1 → Linux2 → 云 Nginx。首次重建 Linux1 MySQL 从库必须显式启用 `BOOTSTRAP_SLAVE`；日常发布禁止重建数据卷。
- Linux1 使用 `linux1.wayuwa.cn` 的 A/AAAA DDNS。云 Nginx 回源仍显式解析 IPv4，避免家庭 IPv6 入站策略未验证时改变生产链路。
- JumpServer 登记 Linux2、Linux1 与云入口三项 SSH 资产；旧 Windows1 资产和 RDP 授权设为停用，不删除历史记录。
- Linux1 同时安装 Java、Maven、Node.js、Docker、GitHub CLI 与 AI CLI，并可运行 WAO AI Runner。交互式 OAuth 和 Runner 配对凭据只留在本机账户，不写入仓库或 Jenkins。

## 后果

生产与开发统一为 Linux 工具链，移除 WSL、PowerShell 发布、Windows 端口代理和 RDP 的运行依赖。Linux1 的开发工作区与生产目录严格分离；开发或 AI Run 可以在同机执行，但不能绕过 Jenkins 的不可变制品与健康检查。

旧 Jenkins 凭据文件可在控制器上原位迁移为 `slave-*` 名称，现有部署公钥继续使用，无需扩大权限。首次上线需要安装 Docker、同步私有环境、创建 GTID 快照并验证公网端口、复制状态、Sentinel、Nacos 与双 Gateway。

## 回退

Linux1 故障时将 slave upstream 保持 `down`，通过 `ALLOW_SLAVE_OFFLINE=true` 显式批准 Linux2 单节点发布；不得自动把旧 Windows1 资产重新启用。恢复双节点时重新部署相同 Git SHA，必要时以 `BOOTSTRAP_SLAVE=true` 从 Linux2 重建从库。
