# ADR-158：Linux1 通过云入口反向通道参与双节点回源

## 状态

已接受（2026-08-29）。

## 上下文

Linux1 位于家庭网络，公网 IPv4 由路由器 NAT 提供。Linux2 可以通过 Linux1 的公网 IPv6 访问
Gateway，但阿里云入口没有 IPv6 默认路由；阿里云使用 Linux1 的公网 IPv4 回源时，还会依赖家庭
路由器的 9000 端口映射。缺少映射或 NAT 回流时，Linux1 节点内验证全部健康，云端回源仍会失败，
Jenkins 也会在发布后检查阶段严格终止。

## 决策

- Linux1 使用独立、受限的 SSH 密钥主动连接云入口，建立
  `127.0.0.1:9000 -> Linux1 127.0.0.1:9000` 的远程 TCP 转发。
- 通道由 Linux1 的 systemd user service 常驻，并通过 `ServerAliveInterval`、
  `ExitOnForwardFailure` 和 `Restart=always` 自动恢复。私钥只保存在 Linux1，不进入 Git、Jenkins 或云机。
- 云机使用专用的 `wao-tunnel` 非特权账号；公钥只允许端口转发并限制
  `permitlisten="127.0.0.1:9000"`。监听地址固定为 loopback，不增加公网开放端口。
- 云机在 `/etc/hosts` 将 `linux1.wayuwa.cn` 映射到 `127.0.0.1`，让既有证书主机名、健康检查和
  Nginx IPv4 渲染逻辑保持一致。Linux1 的 A/AAAA DDNS 继续用于 SSH 和其他节点发现。
- 云端静态资源/Nginx 安装会覆盖运行中的 upstream 文件。流水线必须在云端安装后立即恢复已验证的
  master/slave upstream，再执行最终回源和线上检查；不能只依赖退出清理，因为 Jenkins 控制器随后会
  安全重启。
- 流水线打包前必须验证生产共享环境明确声明 Linux2 master、Linux1 slave 和两台 Sentinel，拒绝把
  旧拓扑配置继续传播到两个应用节点。

## 后果

云入口无需 IPv6，也不依赖家庭路由器公开 9000，即可稳定回源 Linux1。Linux1 或通道故障时，
Nginx 的失败摘流和 Jenkins 的严格健康检查仍按既有策略工作；Linux2 保持主节点并继续提供服务。

通道成为 Linux1 参与公网双节点的主机级前置条件。重装 Linux1 或云入口时必须重新生成专用密钥、
核验云 SSH 主机指纹、恢复 systemd 服务与云机 loopback 映射，然后从云机执行
`curl -4 -kfsS https://linux1.wayuwa.cn:9000/api/hello` 验收。

## 回退

通道不可恢复时将 slave upstream 保持 `down`，仅在明确设置 `ALLOW_SLAVE_OFFLINE=true` 后进行
Linux2 单节点发布。不得为绕过检查而关闭云端回源探针，也不得把云机远程转发监听到公网地址。
