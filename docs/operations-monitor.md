# Windows 运维通知

Jenkins、运行协调器和隧道应安装为自动启动的 Windows 服务。配置 Windows 服务恢复时同时启用非崩溃失败恢复，并用递增的重试间隔避免启动阶段的快速循环；保留服务注册表及包装器配置备份。不要通过强杀数据库验证恢复。

`deploy/scripts/watch-windows-operations.ps1` 每分钟由独立 SYSTEM 计划任务执行一次，检查服务、健康端点、最近完成的 Jenkins 构建和协调器发布结果。两次连续失败发一条告警，两次连续成功发恢复通知；故障持续期间不重复发送。新发布尚未完成的前十分钟暂停配置为 deploymentSensitive 的 HTTP 检查，服务与 Jenkins 检查继续执行。脚本只观察，不抢占运行协调器或重启数据库。

监控配置、state.json、锁文件和 inbox 均放在源码之外的私有目录，ACL 仅允许运维管理员、SYSTEM 和后端服务账号访问。配置示例（替换本机路径；凭据文件不要提交）：

```json
{
  "services": ["WAO-Jenkins", "WAO-Runtime", "WAO-CloudTunnel"],
  "runtimeRoot": "C:/WAO-private/runtime",
  "inbox": "C:/WAO-private/operations/inbox",
  "endpoints": [
    {"name":"Jenkins", "url":"http://127.0.0.1:8088/login", "kind":"http", "deploymentSensitive":false},
    {"name":"WAO 后端", "url":"http://127.0.0.1:8080/actuator/health", "kind":"actuator", "deploymentSensitive":true}
  ],
  "jenkinsAccessPath": "C:/WAO-private/jenkins-access.json",
  "jenkinsJobUrl": "http://127.0.0.1:8088/job/production"
}
```

Jenkins 凭据文件使用 `username` 和 `password` 字段（password 可使用 Jenkins API token）。后端私有配置启用 `wao.operations.inbox` 和 `wao.operations.recipient`，recipient 必须是已确认的成员 ID。文件内容不能选择接收人；默认不启用。没有新增公网通知写入接口。

后端每 30 秒读取最多 100 条记录，使用稳定事件 ID 幂等入库，成功后删除文件；数据库或 WAO 不可用时保留事件，恢复后补发。通知仅指定成员可见，遵循其通知总开关。`-TestNotification` 可验证整条投递链路，内容明确标注为测试。

安装计划任务使用启动触发器和每分钟重复触发器、SYSTEM ServiceAccount、IgnoreNew 并发策略、StartWhenAvailable，以及任务失败重试。保留原任务 XML 便于回退；停用任务和清空后端的 inbox 配置即可停用监控，不删除通知或业务数据。

本机完全停机期间无法实时在同机 WAO 通知；该方案不替代异机监控，不能保证绝对可用性。
