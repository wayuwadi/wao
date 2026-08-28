# ADR-011：首个正式版本前采用单次快速交付

## 状态

已采纳；第一个正式版本上线时失效。

## 背景

开发测试阶段原流程在功能分支上执行后端测试、前端检查和容器构建，合并 `main` 后再重复这些步骤并部署。一次小改动需要等待两轮约十分钟，且无法更快获得真实环境反馈。

## 决策

首个正式版本上线前，将本地验证作为开发门禁。AI 或开发者在开始变更前同步最新 `main`，完成与风险相称的本地测试后，可以直接提交并 push `main`。功能分支和 PR 仅在并行协作、较大重构或隔离试验确有价值时使用，不作为部署前强制门禁。

Linux 主节点上的 Jenkins 只响应 `main` push 和手动触发；按 [ADR-144](144-batched-ai-run-delivery.md) 将相邻
push 合成批次。每次运行构建 WAO Web 与 Agent Web，并按 ADR-097 依次严格发布 Windows、Linux 和云端；
任一应用节点失败都将流水线标红，不再降级成单节点成功。
发布完成后执行线上健康检查和飞书登录入口检查，最后发送飞书 webhook 通知。卡片最初包含完整提交说明；
ADR-146 已将其收敛为提交标题清单与成功/失败摘要，完整正文改由 Git 链接按需查阅。

飞书 App ID、Secret 和 `WAO_VAULT_KEY` 继续保存在 Linux 主节点私有部署目录。Jenkins 通过精确 ACL 读取，
生成仅对当前运行可见的临时文件并安全下发 Windows；这些值不进入源码包、Git 历史或日志。Jenkins 自身的
GitHub、Webhook 和 SSH 凭据保存在 `/etc/jenkins/secrets` 并通过 Credential Store 注入。

## 保留的安全边界

本决策只减少重复流程，不授权提交凭据、绕过不可逆数据操作确认、修改已执行 migration 或隐瞒未通过的验证。详细提交信息和线上验收仍是强制要求。

## 正式版前恢复清单

第一个正式版本发布前必须：

1. 恢复 `main` 分支保护、必需 PR、CODEOWNERS Review 和发布环境审批。
2. 恢复 PR 上的后端、前端、容器构建检查，并评估 main 上是否复用制品以避免重复构建。
3. 删除或改写 `AGENTS.md`、`CONTRIBUTING.md` 和相关 skill 中的开发期直接 push 授权。
4. 轮换生产凭据并复核 GitHub Environments、Secret 权限和部署审计记录。
