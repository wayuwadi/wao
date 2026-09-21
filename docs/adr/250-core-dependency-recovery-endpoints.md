# ADR-250：核心应用使用独立的依赖恢复参数

## 问题

Linux1 先前以 MASTER_PUBLIC_HOST 同时推导 MySQL 与 Nacos 地址。仅数据库接管就修改这个变量会把无关依赖一起改动，且实际容器未透传已有的 NACOS_DISCOVERY_ENABLED 应用配置。

## 决定

两台应用和 Agent 新增 MYSQL_WRITER_HOST、MYSQL_WRITER_PORT 覆盖项；缺省保持原有连接。该参数只改变应用连接目标，不执行主库提升、不改变复制来源，也不表示目标已经可写。

两台应用与网关新增 NACOS_RECOVERY_ADDR 覆盖项，并透传 NACOS_DISCOVERY_ENABLED，缺省 true。Nacos 故障时可临时关闭发现，保留 Compose 已配置的同机 Gateway 到 App 静态路由。它不恢复 Nacos 配置/权限，也不提供 Nacos 自动高可用。

私有恢复参数保存在现有部署私有环境，普通页面发布不得覆盖。MySQL 切换必须先完成旧主写入隔离、GTID 追平、备份源调整及发布验证角色调整；本 ADR 不授权跳过这些条件。

## 验收

两套真实 Compose 使用私有环境在内存中渲染，验证写主地址与 Nacos 恢复配置实际传入对应容器，无凭据输出、无生产容器修改。

Linux2 使用当前实际应用和网关镜像、原有数据库与 Redis 连接启动两个临时容器；Nacos 地址指向不可用的 loopback 端口且关闭发现。两进程健康，网关通过静态路由访问应用 /api/edge/ready 成功，包含真实 MySQL 可写检查。未公开端口，后台调度和会话清理禁用，结束后临时容器清理。

验收工具必须复制原容器的 host.docker.internal 映射，否则临时应用无法解析数据库主机；这是测试环境构造条件，不是 Nacos 故障现象。

生产默认配置未切换；真实 Nacos 备用实例接管与 MySQL 提升仍需各自的受控演练。
