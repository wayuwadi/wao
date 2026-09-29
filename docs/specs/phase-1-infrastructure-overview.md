# 基础设施总览

> 说明：本规划形成于 Linux1 退役之前。资产范围和节点数量已由 [ADR-291](../adr/291-linux2-only-deployment-and-wao-recovery.md) 更新，本文中的 Linux1 资源要求不再适用。

入口 `/infrastructure`，工具组默认显示。管理员可查看 Linux1（WAO 从节点）、Linux2（主节点及独立应用）和云服务器（HTTPS 入口）。

`GET /api/infrastructure/overview?hours=1|24|168` 返回 WAO ApiResponse 包装：generatedAt、intervalSeconds、historyHours、nodes。每个节点含静态 id/name/role/address 与最后成功快照：collectedAt、lastAttemptAt、online、stale、error、hostname、os、cpuCores、cpuPercent、memory(total/available/swapTotal/swapUsed)、disks(mount/total/used/available)、loadAverage、uptimeSeconds、warnings、services、history。数值单位为字节/秒/百分比。无权限或无数据为 null 或缺失，禁止伪造零值。主机 CPU 100% 是全部核心，服务 CPU 100% 是一个核心；systemd 首个采样 CPU 未知。

`GET /api/auth/infrastructure` 仅作管理员准入：未登录 401、非管理员 403、管理员与超级管理员 200。采集接口和所有状态仅可经已认证云入口访问。

验收覆盖三节点实机、应用分组、筛选搜索、趋势采样积累、失败保留旧快照、权限拒绝和正常访问。趋势从安装开始积累，不生成历史演示点。不采集凭据、环境变量、进程参数或私人文件。
