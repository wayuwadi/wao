# Windows2 公网就绪监控

依据 [ADR-333](../adr/333-windows2-public-readiness-monitor.md)。WAO 已在 Windows2 原生服务运行，云端 Nginx 的前端和 API 回环上游分别为 25180、29000，经 Windows2 主动建立的隧道转到本机 5180、9000。业务数据库在 Windows2，旧 Linux2 已退役。

历史飞书「WAO 基础设施告警」来自云端 `wao-health-watch.timer`，每分钟调用 `/opt/wao-health-watch/health-watch.py`。按用户 2026-10-12 要求，已取消该监控的故障和恢复通知，见[静默监控规格](../specs/readiness-monitor-without-notifications-2026-10-12.md)。脚本只检查 `https://wao.wayuwa.cn/api/edge/ready` 的 `code=0, data.protocol=1`，保存真实检查记录；服务不再加载通知 webhook。

## 安装与核对

1. 使用已有授权云端管理通道检查 `systemctl cat wao-health-watch.service wao-health-watch.timer`，核对实际脚本路径。不要读取或输出 `/etc/wao-health-watch/notification.env` 中的 webhook。
2. 本地运行 `python -m unittest discover -s deploy/infrastructure -p test_health_watch.py`，确认失败和恢复只记录状态、残留通知配置不会触发发送、旧节点和旧告警状态被清理。
3. 将经校验的脚本与 `wao-health-watch.service` 暂存到云端，核对旧文件哈希并备份，停止 timer 并等待当前 oneshot 结束；原子替换脚本与服务单元，保持既有所有者与权限，执行 `systemctl daemon-reload`。
4. 运行 `python3 /opt/wao-health-watch/health-watch.py --check-only`，应只返回 `{"main": true}`。此模式不通知、不改状态。正常启动一次 `wao-health-watch.service` 后恢复 timer，核对服务退出码、下一次真实定时采样和状态文件。
5. `/var/lib/wao-health-watch/state.json` 的 `healthy`、`failures` 不包含 `linux2`，`notificationsEnabled=false`，不再包含 `notified` 和 `sent`；核对服务不加载通知环境文件以及下一轮真实定时采样。公网必须继续就绪，不为消除提醒伪造健康结果。

云端脚本由独立管理安装维护，Windows Jenkins 的应用制品发布不自动安装该脚本。仓库修正与云端实际安装需分别记录证据，不能把合并说成监控已更新。

## 恢复

安装或检查失败时通过同一授权通道恢复脚本与服务单元备份、执行 `daemon-reload`，检查公网并恢复 timer。恢复旧版本会重新发送通知，须报告取消通知尚未完成。不要删除历史告警、业务数据或所有监控任务。
