# Jenkins 发布与控制器维护

WAO 主站由 Linux2 上的 Jenkins 构建和发布。当前拓扑只有 Linux2 应用节点与云端入口；旧双节点凭据、从库重建参数和 Linux1 紧急发布分支已从现行发布配置撤出。拓扑决策见 [ADR-291](../../docs/adr/291-linux2-only-deployment-and-wao-recovery.md)。

## 发布结果

主分支 push 进入批处理窗口，发布分为以下路径：

- 纯浏览器变更构建不可变静态制品并发布到云端，后端保持不变。
- 后端或部署变更检查 Linux2 与公共入口状态，再原地更新 Linux2，并发布云端静态制品。
- 完整成功要求 Linux2 Gateway、云端静态资源、公共 /api/edge/ready 探针全部通过。
- 只有一个应用节点，Linux2 重启时可能短暂中断；发布不再做双节点轮换或自动回切。

飞书卡片只报告本次构建、Linux2、云端静态资源与公共就绪检查结果。失败卡会标明失败阶段；成功卡不表示浏览器中的每个业务场景都完成验收。

## Jenkins 凭据

当前控制器使用 GitHub SCM 与云端 SSH 身份，不需要旧 Linux1 SSH 密钥或主机指纹：

- SCM 私钥和 GitHub 主机指纹由 Jenkins 专用 Secret 文件管理。
- 云端 SSH 私钥与 known_hosts 由 Jenkins 专用 Secret 文件管理。
- GitHub PAT 和 webhook HMAC Secret 只存在于 Jenkins Secret 文件/Credentials。
- prepare-controller-secrets.sh 可生成 SCM、云端、管理员和 webhook 随机值；按提示安全录入公钥与外部 Secret，不要把私钥复制到仓库或对话。
- install-controller.sh 安装 Jenkins、云端备份作业和 Linux2 上的控制器维护配置。

apply-controller-config.sh 随主发布同步 Jenkins 配置，并停用残留的 peer 备份、数据库隧道、旧 mesh endpoint 和旧证书转发定时任务；备份文件和历史快照保持原样。

## 部署验证

完整验证流程由 Jenkins 入口调用 deploy/jenkins/ci-validate.sh。改动应按影响范围完成静态检查及相称的本地验证；发布后的线上验收由用户负责。

## 关联手册

- [Linux2 单节点异地备份与恢复](../../docs/runbooks/linux2-only-cloud-backup.md)
- [Jenkins 运行时恢复](../../docs/adr/262-jenkins-runtime-recovery.md)
- [本地开发与合并交付流程](../../.codex/skills/wao-dev-batch-delivery/SKILL.md)
