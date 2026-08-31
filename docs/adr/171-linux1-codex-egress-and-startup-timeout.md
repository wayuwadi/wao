# ADR-171：Linux1 Codex 复用 Linux2 VPN 出口并限制启动等待

## 状态

已采纳。

## 背景

Linux1 可以访问 WAO、GitHub 和 `auth.openai.com`，但其本地网络把 `chatgpt.com` 解析到错误地址，
Codex CLI 的额度请求和模型会话因此连接超时。Runner 过去只按“本机存在 codex 命令”上报能力，
`codex exec --json` 启动后又允许无限等待首个事件；一次并发领取五条任务时，五个槽位会全部停在
“工作区已准备完成，正在启动 Codex”，Runner 自更新也因活动任务无法收尾。

## 决策

- Linux1 复用既有到 Linux2 的受限 SSH 常驻通道，增加仅监听 Linux1 回环地址的本地转发：
  `127.0.0.1:17897 -> Linux2 127.0.0.1:7897`。Linux2 端继续使用本机 VPN 代理，不开放新的公网或
  局域网端口。
- Runner 增加 `--codex-proxy-url` / `WAO_CODEX_PROXY_URL`。代理只注入 Codex 子进程、额度读取和
  隔离 Codex 任务；WAO 心跳、Git、Claude Code 与 Qoder 保持原路由。
- 配置专用代理后，每轮官方 Codex 启动前访问 `chatgpt.com/backend-api/wham/usage`；HTTP 401 代表
  已到达真实服务。连接失败或其它状态立即进入既有自动重试并回传稳定错误，不启动一个注定悬挂的 CLI。
  自定义 Responses API 账号不依赖该地址，跳过此探针。
- 所有流式 AI CLI 启动后 90 秒仍没有任何事件时终止整个进程组。目标模式的 app-server 使用相同
  首事件时限；一旦已经产生事件，长时间测试、部署和线上观察仍由任务自己的终态约定管理，不设置粗暴的
  整轮固定时限。

## 后果

Linux1 能通过 Linux2 已有 VPN 出口真实执行 Codex，不需要伪造节点能力。SSH 通道或代理中断时，
任务会在约十秒内得到可定位错误并按项目策略重试；即使探针以外的启动阶段异常，最迟 90 秒也会释放
槽位。该设计仍依赖 Linux2 VPN 和 Linux1→Linux2 通道，二者不可用时应明确失败，不回退到错误地区出口。

## 回退

从 Linux1 Runner 启动参数移除 `--codex-proxy-url`，并从 SSH unit 移除 `-L` 即可恢复原网络行为。
不得通过扩大 Linux2 代理 ACL 或公网开放代理端口来替代回环转发。
