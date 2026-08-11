# ADR-034：Codex 服务端请求固定走 Linux VPN 出口

## 状态

已采纳。补充 ADR-033 的设备码登录部署约束，不改变账号凭据格式和 Runner 的本地执行位置；
AI 编排接口的路由范围后由 ADR-051 收窄。

## 背景

阿里云节点只托管静态前端和 Nginx，`/api/` 通过 `least_conn` 分发到 Linux 主节点与 Windows
从节点。Codex 设备码申请、授权结果轮询、令牌交换/刷新和额度查询都由收到请求的 `wao-server`
访问 OpenAI；请求落到没有 VPN 出口的节点时，OpenAI 返回 `unsupported_country_region_territory`。

只把登录起点固定到 Linux 仍不完整：Runner 同步账号或领取任务时也可能刷新即将过期的 Codex 令牌。
最初因此覆盖了 AI 账号管理与 AI Runner 的完整服务端链路；ADR-051 已把入队调整为仅做数据库校验，
并将不发生供应商出站的编排接口恢复到双节点路由。

## 决策

- 云端 Nginx 新增只包含 Linux 主节点的 `wao_ai_gateway`；按 ADR-051，账号管理、集中凭据下发和
  Runner 领取凭据固定走该 upstream，其余 AI 编排接口与普通 `/api/` 在主从 Gateway 间分流。
- AI 专用 upstream 不配置 Windows backup。Linux 不可用时相关请求明确失败，不能静默切换到会被
  OpenAI 区域策略拒绝的出口。响应增加 `X-WAO-API-Route: linux-vpn-master` 便于验收和排障。
- 每次云前端部署同时渲染并原子更新 Nginx 配置；`nginx -t` 失败时恢复上一份配置，不留下半部署状态。
- Linux 主节点运行一个使用 host 网络的 tinyproxy，仅允许回环地址与 Docker `172.16.0.0/12` 私网访问。
  `wao-server` 保持 bridge 网络，只为 `OpenAiCodexOAuthClient` 配置主节点物理网卡地址
  `http://${MASTER_BIND_IP}:18888`，未设置时才回退 `host.docker.internal`。物理地址避免自定义 bridge 跨到
  Docker 默认 bridge 网关时被隔离；代理仍共享主机 VPN/TUN 路由，但飞书、微信、Claude 和自定义 API
  继续使用原有出站路径。代理不声明端口映射，公网及其他局域网来源由 ACL 拒绝。
- 主节点部署后，流水线先从 host-network 代理容器直连 OpenAI，确认主机 VPN 返回 HTTP 200，再从 Linux 的
  `wao-server` 容器读取同一代理配置并经代理发起无凭据探针。失败时输出不含凭据的 tinyproxy 末尾日志。
  403 代表主机 VPN 出口仍未被代理链路采用，要修复主节点网络而不是把失败流量放回从节点。
- 该探针是**告警而非上线门禁**（`continue-on-error`）。VPN 出口属于主节点的运行时网络环境，与本次构建
  产物无关：机场掉线、TUN 路由变化都会让探针变红，而此时前端、网关和全部非 AI 接口都是好的。把它当作
  发布成功的判据，等于让一个只影响 Codex 的环境问题挡住全站发布，也会把飞书部署卡片误报成失败。
  探针失败时流水线继续，run 摘要保留 warning 注解。

## 后果

- 阿里云 VM 不需要 VPN；浏览器仍需能打开 OpenAI 官方设备确认页。
- Codex 登录、刷新、额度查询和集中账号下发统一从 Linux `wao-server` 出站，不再受 Nginx 随机分流影响。
- 容器无需接管主机网络或全局代理；只有 Codex OAuth 客户端通过最小权限的内部 HTTP 代理复用 VPN。
- AI 账号、集中凭据下发及 Runner 领取凭据依赖 Linux 主节点；AI 编排与普通业务 API 保留双节点容错。
- Linux 主机配置 VPN 不等于容器一定生效，部署探针会把该差异显式暴露出来。
- 代价是 VPN 掉线时流水线仍报绿，要靠 run 摘要的 warning 注解发现；换来的是普通业务发布不被 AI 出口的
  环境问题阻断。
