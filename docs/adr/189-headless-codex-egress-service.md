# ADR-189：Codex 出站脱离 Linux 桌面会话常驻

## 状态

已采纳。替代 ADR-034 中“依赖主机 TUN 路由”的实现细节，以及 ADR-171 中 Linux1 直接转发到
Clash Verge 桌面进程的生命周期假设；AI 专用路由、回环 ACL 和凭据边界不变。

## 背景

Linux2 的 Codex 出站依赖 Clash Verge 图形进程。桌面用户退出后，mihomo 与本机 `7897` 端口一起消失，
但 `codex-egress-proxy` 自己的 `18888` 端口仍在监听，原 Docker 健康检查因此继续显示健康。Linux 主节点
Runner 随后领取多条 AI Run，直到 Codex 请求 `chatgpt.com/backend-api/codex/responses` 才统一失败；流水线
里的出站探针虽然留下告警，也不会恢复运行时依赖。

Linux1 通过 SSH 回环转发复用 Linux2 的 `7897`，因此同一个桌面进程退出还会同时切断从节点的 Codex 出站。
仅重启 Runner 或内部 tinyproxy 无法恢复这条链路。

## 决策

- Linux2 发布时由 Compose 启动 `codex-vpn` 服务。容器只读挂载系统已安装的静态 `verge-mihomo`，并以
  部署用户 UID/GID 读取 Clash Verge 已生成的私有 `clash-verge.yaml`；订阅、节点和账号数据不复制到仓库、
  镜像或日志。配置缺失或校验失败时拒绝启动。
- 服务不依赖图形会话，异常退出后由 Docker 自动重启。每次主节点部署都会校验配置并强制重建它；启动脚本
  只在容器临时配置中允许 WAO bridge 访问，宿主的 Clash Verge 私有配置不被改写。
- `codex-vpn` 与 tinyproxy 都加入 WAO bridge，tinyproxy 直接把 HTTPS 请求上送到 `codex-vpn:7897`；
  `wao-server` 再通过服务名访问 ACL 受限的 `18888`，整条应用链路不穿越主机防火墙。mihomo 的 7897 只发布到
  宿主 `127.0.0.1` 供 Linux1 SSH 回环转发复用，不增加公网或局域网监听。
- `codex-egress-proxy` 的容器健康检查改为经 `18888 -> 7897` 访问真实 ChatGPT；只检查本地端口不再算健康。
  发布后继续暴露 `codex-vpn` 和内部代理的真实链路故障。
- WAO 核心服务先独立完成健康检查，再启动 Codex 出口。出口配置、启动或真实链路失败时，生产总体验证
  明确告警，但不阻止 Linux2 的业务部署和回流；AI Run 的出口故障仍需单独修复。

## 后果

- Linux2 无人登录或桌面重启后，AI Run、集中账号刷新和 Linux1 转发仍有稳定出站。
- Clash Verge 继续作为私有配置管理界面，但不再拥有生产核心进程的生命周期；修改配置后下一次发布会
  校验并重建服务，运维也可重建 `codex-vpn` 立即加载。
- 机场节点失效、订阅过期或 ChatGPT 链路不可达会显示为真实健康失败，而不是“容器健康、任务批量失败”。

## 回退

移除 tinyproxy 的 `Upstream` 配置、恢复其 host 网络并停用 `codex-vpn`，即可回到主机 TUN 路由。回退前必须
确认图形 Clash Verge 已常驻且真实 ChatGPT 探针通过；不得通过放宽 tinyproxy ACL 或增加直连回退绕过区域策略。
