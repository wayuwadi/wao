# Windows 公网守护操作

## 当前实际链路

云 Nginx 的静态制品独立保存；API 当前经既有 SSH loopback 转发到 Windows 网关，再访问本机 `wao_local` 开发库。Linux2 是 ADR-291 的正式目标，当前这条临时运行链路不证明 Linux2 生产数据已恢复。切回 Linux2 前必须独立核对最新权威数据、恢复点、库差异和用户授权，不能直接改 upstream。

## 系统托管

运行目录沿用 `deploy/data/windows-local/`，私有 YAML、环境、SSH 密钥、数据目录和版本 launcher 均保留。复制三个守护文件并注册 `WAO-Public-Runtime`：

```powershell
./deploy/scripts/manage-windows-public-runtime.ps1 -Action Install -RuntimeDirectory <现有运行目录> -RegisterOnly -IntegrateLegacyLaunchers
```

任务以当前用户普通权限、交互登录令牌运行，使用系统自带 Windows PowerShell；登录及每分钟触发，忽略重复实例，不设执行时限，失败一分钟重试。没有保存登录密码或提升账号权限。`RegisterOnly` 注册为禁用状态，便于先核对再交接。

## 无中断交接

1. 用 `Get-OwnedRuntimeProcess` 核对数据库、后端、网关、relay 和备份 worker 的 PID、精确启动时间、进程名和脚本路径。异常时停在此处，不根据端口号杀进程。
2. 保留旧守护，启用并启动系统任务。新守护接管所有现存组件，核对 `public-runtime.status.json` 的 PID 与之前完全一致且 `ready=true`。
3. 只结束旧守护的精确 PID，不用 `/T` 或递归结束子进程。任务的进程父级应为 Windows Task Scheduler 的系统宿主，而不是 Codex。
4. 核对公网 `/api/edge/ready`、`/api/hello`、版本和已登录页面真实数据。保留应用制品，不为守护变更重启 Java 或数据库。

任务管理脚本的 `Resume` 会启用并立即启动原任务；已有独占锁使重复启动不创建副本。

```powershell
<运行目录>/manage-windows-public-runtime.ps1 -Action Status -RuntimeDirectory <运行目录>
<运行目录>/manage-windows-public-runtime.ps1 -Action Pause -RuntimeDirectory <运行目录>
<运行目录>/manage-windows-public-runtime.ps1 -Action Resume -RuntimeDirectory <运行目录>
```

`Pause` 只暂停自动守护，保持当前业务进程。`Stop` 先写暂停标记，再停止登记且身份匹配的公网应用及转发，保留 MySQL 和备份；后续计划触发不会抵消人为停服。原私有 `start-public.ps1` / `stop-public.ps1` 接到此管理脚本后仍保留对应启动/停服语义。

## 诊断与恢复边界

飞书群聊收集：当前临时后端沿用 `local-cloud` 并关闭全局调度。部署 launcher 需显式添加 `--wao.feishu.collection-scheduling-enabled=true`，只启动飞书轮询、收件处理和清理（ADR-327）。群聊收集数据库开关另行控制是否读取；普通本地验收实例不添加该参数。启用后检查真实群读取及已持久收件结果，不将开关保存成功当作调度运行成功。

- `public-runtime.events.jsonl` 记录组件接管、退出、恢复、健康变化、暂停及启动失败。只写时间、组件、PID 和固定原因或异常类型，不写凭据、完整参数或响应正文。
- `public-runtime.status.json` 记录当前 PID 与网关就绪。启动日志在重启前留存到 `runtime-log-history/`，不自动删除历史文件。
- 锁文件可以留在磁盘；是否持锁由操作系统文件句柄决定，不能以文件存在认定有进程，更不能删除数据库文件来恢复。
- 未登记的端口占用会阻断对应启动，守护不结束其他程序。数据库 launcher 只接受已有初始化目录。
- 正常空闲睡眠被现有运行期间的执行状态请求抑制，显示器可休眠；主动睡眠、断电、断网及退出 Windows 登录仍会影响 API。
- 当前仍是单机依赖。系统任务解决守护无人恢复和 Codex 生命周期耦合，不提供双机容灾。不能为了验证故障而关闭 Codex、退出登录、停止数据库或重置线上数据。

## 验证记录（2026-10-07）

- PowerShell 7 与系统 Windows PowerShell 5.1 完成隔离脚本回归，覆盖接管、PID 过期、错误脚本、重复实例锁、relay 退出恢复、数据库启动阻断、暂停及主动停止。
- 实际交接保持五个业务/基础进程 PID 不变，系统任务的父进程为 `svchost.exe`。
- 只结束任务托管的守护后，计划任务自动在 49.8 秒恢复；24 次公网就绪采样无失败，五个组件 PID 保持不变。
- 原始停止的操作者未被事件审计记录确认；主机未重启，应用日志在 2026-10-06 09:36 后中断、10:13 重新启动，只能作为相关时间线，不作为直接因果证明。
