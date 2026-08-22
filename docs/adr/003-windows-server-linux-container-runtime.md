# ADR-003：Windows 从节点使用 WSL2 Docker Engine

## 状态

已采纳。

## 上下文

从节点最初运行 Windows Server，系统重装后迁移到 Windows 11 Pro。项目所有生产镜像仍是 Linux 镜像；虽然 Windows 11 支持 Docker Desktop，但生产节点不应依赖交互式桌面程序的启动、升级和授权生命周期。

## 决策

从节点使用 Windows 11 Pro WSL2，安装无桌面的 Ubuntu 24.04，并在发行版内安装 Docker Engine 与 Compose 插件。Compose 文件保存在 `D:\deploy\wao`，由 WSL 通过 `/mnt/d/deploy/wao` 访问；数据使用 Docker named volumes，避免数据库落在 `/mnt/d`。发行版注册在 CI/CD 登录账号 `wage` 名下，VHD 位于 `D:\WSL\WAO-Ubuntu`。

容器端口发布到 WSL 的所有地址，再由 Windows `netsh interface portproxy` 监听 `0.0.0.0` 并转发到 WSL 动态 IP。WSL 限制为 4 核、8GB 内存和 2GB swap；Windows 原生计划任务保持 WSL 在线、在开机后刷新端口转发、每五分钟刷新公网注册、每日备份并每月执行恢复演练。生产服务不依赖桌面登录；原生 AI Runner 仍使用 `wage` 的交互登录态。公网通信决策见 ADR-005。

## 后果

- 保留既定 Linux 镜像、Docker Compose 和双节点拓扑。
- 不依赖 Docker Desktop，Windows 11 更新和桌面登录不会成为容器启动前置。
- Windows/WSL 重启后的启动、Docker 恢复和端口刷新由计划任务与部署脚本自动完成。
- 如从节点进入严格 7x24 生产阶段，独立 Hyper-V Linux VM 是更稳健的后续迁移方向。
