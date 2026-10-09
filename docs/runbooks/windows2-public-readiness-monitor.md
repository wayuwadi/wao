# Windows2 公网就绪监控

依据 [ADR-333](../adr/333-windows2-public-readiness-monitor.md)。WAO 已在 Windows2 原生服务运行，云端 Nginx 的前端和 API 回环上游分别为 25180、29000，经 Windows2 主动建立的隧道转到本机 5180、9000。业务数据库在 Windows2，旧 Linux2 已退役。

飞书的「WAO 基础设施告警」来自云端 `wao-health-watch.timer`，每分钟调用 `/opt/wao-health-watch/health-watch.py`，并非浏览器弹窗或本机 Jenkins 发布通知。脚本当前只检查 `https://wao.wayuwa.cn/api/edge/ready` 的 `code=0, data.protocol=1`。保留故障告警，只移除退役节点。

## 安装与核对

1. 使用已有授权云端管理通道检查 `systemctl cat wao-health-watch.service wao-health-watch.timer`，核对实际脚本路径。不要读取或输出 `/etc/wao-health-watch/notification.env` 中的 webhook。
2. 本地运行 `python -m unittest discover -s deploy/infrastructure -p test_health_watch.py`，确认旧节点状态被清理、公网故障仍告警、抖动去重与通知失败重试。
3. 将经校验的脚本暂存到云端，核对旧文件哈希、备份旧文件，停止 timer 并等待当前 oneshot 结束；原子替换脚本，保持既有所有者与权限。
4. 运行 `python3 /opt/wao-health-watch/health-watch.py --check-only`，应只返回 `{"main": true}`。此模式不通知、不改状态。正常启动一次 `wao-health-watch.service` 后恢复 timer，核对服务退出码、下一次真实定时采样和状态文件。
5. `/var/lib/wao-health-watch/state.json` 的 `healthy`、`failures`、`notified` 不再包含 `linux2`；公网必须继续就绪。已有公网事故保留原恢复与去重逻辑，不为消除旧节点提醒伪造健康结果。

云端脚本由独立管理安装维护，Windows Jenkins 的应用制品发布不自动安装该脚本。仓库修正与云端实际安装需分别记录证据，不能把合并说成监控已更新。

## 恢复

安装或检查失败时通过同一授权通道恢复本次备份，保留通知配置与状态文件；检查公网并恢复 timer。恢复旧脚本也会恢复退役节点误报，应明确报告未完成。不要删除历史告警、业务数据或所有监控任务。
