# ADR-003：Windows Server 从节点使用 WSL2 Docker Engine

## 状态

已采纳。

## 上下文

原方案计划在 Windows Server 2025 上使用 Docker Desktop 的 WSL2 后端运行 Linux 容器。Docker Desktop 的官方支持范围是 Windows 10/11，不包括 Windows Server；Windows Server 原生容器运行时面向 Windows 容器，不能承载本项目的 MySQL、Redis、Nacos 和 Alpine/Temurin Linux 镜像。

## 决策

从节点启用 Windows Server 2025 官方支持的 WSL2，安装 Ubuntu 24.04，并在发行版内按 Docker 官方 Ubuntu 仓库安装 Docker Engine 与 Compose 插件。Compose 文件保存在 `D:\deploy\wao`，由 WSL 通过 `/mnt/d/deploy/wao` 访问；数据使用 Docker named volumes，避免数据库落在 `/mnt/d`。

Windows 主机运行 Tailscale。容器端口发布到 WSL 的所有地址，再由 Windows `netsh interface portproxy` 仅监听 Tailscale IPv4 并转发到 WSL 动态 IP。每次 WSL 重启后必须刷新转发规则。

## 后果

- 保留既定 Linux 镜像、Docker Compose 和双节点拓扑。
- 不依赖未受支持的 Docker Desktop。
- Windows/WSL 重启后需要先启动 WSL Docker 并刷新端口转发；仓库提供自动化脚本。
- 如从节点进入严格 7x24 生产阶段，独立 Hyper-V Linux VM 是更稳健的后续迁移方向。
