# 部署和数据保护

公开 `main` 是唯一源码来源。公开 CI 运行无生产凭据的检查；维护者 Jenkins 串行构建受信任 `main`、保存不可变制品，并交给既有运行协调器。数据库、上传文件、私有配置和密码库密钥不属于 Git 工作区。

## 日常发布策略

有写入权限的开发者可在验证通过后自行合并 PR，不要求第二人审批。`main` 仍要求 `secrets`、`local-install` 检查通过、分支保持最新并解决审阅讨论；禁止强推和删除。合并后沿既有 Jenkins 轮询、静默窗口和串行协调器发布，核对实际版本、就绪状态及相关页面后才报告上线。此策略不向 Fork、PR 或无写入权限的贡献者授予生产凭据。

## 现有 Windows 生产安装

Jenkins 使用 `deploy/jenkins/windows-build-deploy.ps1`，由维护者提供独立的 `-RuntimeRoot` 和 `-SourceRoot`。运行根目录保留工具、配置、发布队列与历史制品；源码可以重新检出，但运行根目录不能被 `git clean` 或源码更新覆盖。

服务器私有 `deploy/data/windows-server/production-build.private.json` 配置：

```json
{
  "legacyMigrationsDirectory": "C:/WAO-private/legacy-migrations",
  "legacyMigrationSha256": "<SHA256 of the original V33 resource>"
}
```

V33 从当前部署制品原样取得，保持字节和 SHA256，不手工重写。Maven 的 `legacy-migration-compat` profile 将其复制到构建资源，公开源码不保存旧 Webhook。构建脚本再次验证最终 JAR 中的字节哈希；资源缺失、哈希错误或位于公开源码目录内时拒绝发布。

带兼容资源的生产 JAR 仍含旧迁移内容，只保存在私有构建和生产目录，不能上传到公开 Release、制品附件或源码压缩包。面向社区的制品须从公开源码进行普通构建，不启用此私有 profile。

因此原数据库的已执行 Flyway 校验和保持有效，不运行 `repair`，不修改历史表，也不重新执行旧初始化 SQL。新安装不提供兼容目录，直接使用公开迁移和独立空数据库。

首次切换时，私有 `open-source-commit-map.private` 把旧发布 SHA 对应到脱敏后的同一版本。后续直接使用公开历史；找不到唯一映射时构建失败，不猜测发布范围。

## 切换与回退

1. 备份并核对原 Git 镜像和本地 bundle；保存 Jenkins job、构建入口、配置、当前版本和制品哈希。
2. 创建数据库一致性备份，同时保护匹配的密钥与文件资源；记录数据目录和 Flyway 校验和。
3. 保持当前服务运行，先通过安全检查和测试，再仅暂存构建，核对 JAR 兼容资源、版本和制品完整性。
4. 等运行批次结束后切换 Jenkins SCM 和入口，从公开 `main` 发布，核对实际版本、公网就绪和持久数据。
5. 失败时恢复旧 job 和构建入口，沿现有协调器切回原制品。不要通过覆盖业务数据库处理发布失败。

真实备份位置与验证结果存放在私有运维记录，不把凭据或数据库转储提交到公开仓库。
