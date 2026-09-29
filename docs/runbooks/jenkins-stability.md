# Jenkins 提交不构建与运行时恢复

## 快速定位

1. 看 `wao-main-cd` 是否有新构建；未出现时先看队列原因及 `GitHub Hook Log`，不能只看 webhook HTTP 200。
2. Linux2 执行 `systemctl status wao-jenkins-health.timer wao-jenkins-health.service`，
   `sudo cat /var/lib/wao-jenkins-health/status.json` 和
   `journalctl -u wao-jenkins-health --since '30 minutes ago'`。
3. `spawn_helper_failed` 表示 JVM 无法创建子进程；对照 `/var/log/dpkg.log` 的 OpenJDK 更新时间
   与 `systemctl show jenkins -p ActiveEnterTimestamp`。探针在无运行构建、连续两次确认后请求安全重启。
4. `scm_polling_failed` 查 `/var/lib/jenkins/jobs/wao-main-cd/github-polling.log` 和 `polling.log`。
   队列会记录静默窗口、执行器与前序构建原因；长时间等待只报告，不自动取消任务。
5. 重启后等待 SCM 兜底轮询（至多约五分钟），再加原有五分钟静默窗口；核对新构建由 push 或 SCM 触发，
   完成检出并回写 GitHub pending。发布成功还需最终生产验收，不能将入队等同上线。

## 手工探针

```sh
sudo -u jenkins python3 /usr/local/lib/wao-jenkins/controller-health.py \
  --state-dir /tmp/wao-jenkins-manual-check
```

默认只检查；只有 systemd 服务带 `--recover`，不要随意对生产做故障注入。
HTTP 不可用时不会自动重启；先检查服务、日志、磁盘和本机 8081 监听。

## 维护约定

JDK 或 Jenkins 升级需要检查新 JVM 已加载，空闲时安全重启并执行探针；保持安全更新开启。
探针异常进入 journal 和状态文件，尚未接入独立外部告警。后续若接入，必须覆盖探针自身停止、
主机失联及提交超过预期未构建，并明确通知渠道和去重策略。

发布涉及 `deploy/jumpserver/` 且失败在资产 SSH 验收时，检查 Linux2 部署是否已完成，再确认流水线用
Linux2 当前生成的 `.jumpserver-asset-key.pub` 更新云入口授权后才运行 `verify-assets.sh`。JumpServer
持久密钥可能在部署时首次生成；提前同步旧公钥会导致 Linux2 验收通过、云入口认证失败。

2026-09-24 故障的证据和恢复边界见 ADR-262。
