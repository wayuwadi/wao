# ADR-092：Windows 离线时继续完成 Linux 发布

## 状态

已被 [ADR-097](097-strict-dual-node-delivery.md) 取代。以下内容仅保留为历史记录。

## 背景

日常滚动发布原本允许 Windows / WSL 从节点离线时跳过，但 V77 首次安全窗口要求两台应用节点同时可达。
从节点长期离线会让云前端和 Linux 主节点也一直停留在旧版本，导致已经合并并通过测试的功能无法交付。

## 决策

- Windows 从节点离线或 SSH 不可达时，流水线必须把 slave upstream 保持为 `down`，但不得因此阻塞云前端和
  Linux 主节点发布；从节点上线后由下一次 `main` 发布补齐。
- V77 安全窗口遇到离线从节点时，仍暂停飞书聊天定时同步并停止 Linux 旧服务，再部署云前端和 Linux 新服务。
  Linux 健康后恢复 master upstream，保证当前可用节点运行新版本。
- Linux 单节点完成时不写入 `deployment.v77.rollout-state = complete`，也不恢复被暂停的飞书同步任务；这两项
  保持为待办，直到某次发布确认 Windows 可达、停止旧服务并完成新版本部署。
- Windows 可达但停止或升级失败时仍然 fail-closed，不把“明确失败”降级为“离线跳过”。

## 结果

从节点关机不再拖住 Linux 交付，线上功能以 Linux 主节点版本为准；同时，Nginx 不会把请求送到旧 Windows
节点，V77 的全局完成标记也不会伪报。代价是从节点离线期间飞书聊天定时同步保持暂停，待其补齐后自动恢复原状态。
