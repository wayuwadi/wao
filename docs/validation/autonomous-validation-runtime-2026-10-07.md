# 自主建设隔离验证环境验收（2026-10-07）

## 实际问题

首条真实首页建设记录已完成四个文件的半成品修改，但工作区没有前端依赖、没有已配置浏览器通道，执行器正确保留 FAILED，没有提交、合并或发布该修复。主工作区仍有其他任务的未提交改动，且 manifest 旧于运行基线，不能直接借用或覆盖。

## 本地验证

- DevelopmentEnvironment / DevelopmentPlan / DevelopmentWorkerRecovery / DevelopmentService / DevelopmentDecisionService / FlywayMigrations：72 项通过。
- 显式真实 DevelopmentCodexProcessSmokeTest：1 项通过（82.692 秒）。read-only 实际读取指定 fixture；workspace-write 实际修改指定文件，并在本次 write-output 写入 fixture-artifact-423，未联网或读取其他用户资料。
- Windows 含空格真实 junction、重试幂等、两个 manifest 不匹配拒绝、独立依赖 checkout origin 与 state 边界、dirty 主工作区保持原样、上下文字节/UTF-8/越界拒绝、rebase 成功与冲突现场保留均有回归覆盖。
- 专用依赖 clone 按现有锁文件 npm ci，不升级依赖；自身 Git clean。私人验收上下文和 helper 位于仓库外，不进入本 PR。
- 真实成员正常登录只读回环桥接：GET 自主建设状态成功，PUT 返回 405；会话仅在进程内，禁止认证令牌读取和跨域重定向。
- 使用专用依赖、Vite runner 与专用产物缓存启动浏览器页面，CUA 确认真正成员、实际运行记录、失败原因、三方案和重试入口可见；没有写入演示业务数据。
- 后端 package 通过；前端构建、类型检查和 UI 增量检查通过。工程一致性检查 0 错误、5 个既有 ADR 重号警告；本 PR 不新增 ADR 或 migration。

## 交付边界

该 PR 修复验证环境，实际自主修复需要部署后从保留工作区重试，再逐项核对测试、PR 与公网版本。上述本地通过不等于首条自主修复已交付。真实飞书卡片的人类回复及实体设备体验仍待实际验收；不编造确认或关闭用户任务。
