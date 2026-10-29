# Windows 运维告警群

Jenkins、运行协调器和隧道安装为自动启动的 Windows 服务。启用非崩溃失败恢复，采用 15 秒、60 秒、120 秒递增重试间隔；保留服务注册表及包装器配置备份。不要通过强杀数据库验证恢复。

`deploy/scripts/watch-windows-operations.ps1` 由独立 SYSTEM 计划任务每分钟运行，检查服务、健康端点、最近完成的 Jenkins 构建和协调器发布结果。连续两次失败发告警，两次成功发恢复通知，持续异常不重复通知。待发布请求尚未完成的前十分钟暂停 deploymentSensitive HTTP 检查，服务和 Jenkins 检查继续。脚本只观察，不抢占协调器或重启数据库。

配置、状态、锁文件、队列和群 Webhook 凭据放在源码之外的私有目录，ACL 只允许运维管理员和 SYSTEM。Webhook 沿用用户确认的既有群配置，使用 Windows DPAPI LocalMachine 加密写入文件。本机管理员仍具备解密能力，必须保留文件 ACL。群配置变更或停用时同步更新/移除监控凭据；它不是数据库配置的自动镜像。

配置示例（实际凭据不要提交）：

```json
{
  "services": ["WAO-Jenkins", "WAO-Runtime", "WAO-CloudTunnel"],
  "runtimeRoot": "C:/WAO-private/runtime",
  "inbox": "C:/WAO-private/operations/inbox",
  "webhookCredentialPath": "C:/WAO-private/operations/group-webhook.dpapi",
  "policyBaseUrl": "https://wao.example.com/api/public/notification-settings",
  "endpoints": [
    {"name":"Jenkins", "url":"http://127.0.0.1:8088/login", "kind":"http", "deploymentSensitive":false},
    {"name":"WAO 后端", "url":"http://127.0.0.1:8080/actuator/health", "kind":"actuator", "deploymentSensitive":true}
  ],
  "jenkinsAccessPath": "C:/WAO-private/jenkins-access.json",
  "jenkinsJobUrl": "http://127.0.0.1:8088/job/production"
}
```

Jenkins 凭据文件使用 username/password 字段，password 可使用 API token。脚本不输出凭据。计划任务配置启动触发器和每分钟重复触发器、SYSTEM ServiceAccount、IgnoreNew、StartWhenAvailable、失败重试和两分钟运行上限；更新前导出任务 XML。

告警先持久化，再由 `send-windows-operation-alerts.ps1` 发送至既有飞书告警群。只有飞书明确返回成功才删除队列文件；失败在下次巡检重试。沿用现有 Jenkins 成功/失败通知门禁，包括群总开关和沉默时段；策略接口不可达时只允许故障通知兜底，恢复/测试通知等待策略恢复。单次最多处理十条。

`-TestNotification` 生成明确标注的测试通知。消息包含稳定事件编号，通常不重复；飞书收到后响应丢失等网络不确定场景可能重发同一编号，无法承诺端到端恰好一次投递。本机完全停机时无法发送，此方案不替代异机监控。

回退：停用计划任务，恢复服务注册表中的原恢复策略。保留状态和队列供追溯，不删除业务数据。
