# ADR-216：WAO 通过受限 API 向独立 WeKnora 同步派生内容

## 状态
已采纳。

## 决策
- 业务导出、配置、映射与持久化运行记录留在 WAO；复用 Automation 的 Cron 抢占，真实执行由持久化队列异步推进。
- 双节点共用 MySQL 命名锁，同一时间只有一个同步执行者。进程退出连接释放后可恢复未完成任务；逐项写入映射，内容指纹与远端来源标识共同实现重试幂等。
- 调用 WeKnora v0.8 官方 manual knowledge API，避免修改上游表结构或让 WeKnora 直接读取 WAO 数据库。升级只需检查 API 契约。
- 云 Nginx 提供限定知识写入路由的机器接口，必须提供 X-API-Key；清除 Cookie/Bearer，WeKnora 自身验证 key 与知识库范围。密钥用现有 VaultCryptoService 加密保存，范围限定到专用知识库。
- 目标知识库身份确定后不允许在有映射时随意改绑，避免误清其他库。默认 Wiki-only，无本地模型。
- 按模块显式导出共享业务内容与配置者个人内容；不使用 SELECT *、全库复制或通用对象序列化。文档、聊天和模型产生的内容均只是资料，不是同步执行指令。
- 模型处理异步且消耗额度，因此设置每轮上限，区分传输与索引状态，不把接口成功包装成已可问答。

## 首次凭据引导

服务器所有者使用 `deploy/weknora/provision-sync-key.py` 在现有 v0.8 表中初始化只保存哈希的机器凭据，绑定专用 Wiki 库，仅授予 retrieve / ingest。原始 Key 只保存在 root 0600 文件，纳入备份。`configure-wao-sync.py` 在 Flyway 上线后用既有 WAO Vault key 加密导入一次；后续调整走产品接口。引导不创建用户、不修改上游 schema，升级时需核对这一引导工具的表结构契约。
