# 本机真实 MySQL CLI 验收

只在显式 opt-in 时运行。入口在 test classpath，不会进入生产 JAR；Node 实际调用仓库 CLI 与 HTTP MCP，业务服务和 MySQL 为真实实例，仅外部飞书通知出口关闭。Spring 使用 `local-cloud`、127.0.0.1:18084，禁用共享调度及 AI，并在明确本机连接后应用正常 Flyway 迁移。默认不会自动运行，不建立假成员、不触碰已有用户内容、不删除验收记录。

先协调 Maven `target` 空闲时段，再执行：

```powershell
./deploy/scripts/acceptance-workspace-cli-mysql.ps1 -ConfirmLocalDatabase -EnvFile C:/Users/wage/Documents/Code/wao/.env.windows-local
```

脚本只在内存读取该环境文件的数据库变量，检查回环数据库地址和本机 MySQL / Docker / WSL 监听；Java 再确认真实 MySQL 数据库名称及可写状态。真实成员默认选 active account 对应 name=wage，找不到则选 id=wg，也可显式 `-MemberId ID`。所有新项目、任务、小记、知识库、模板、标签都带 `[MCP CLI 本地验收]` 和随机前缀。临时逐项授权令牌仅在 Java / Node 内存及 stdin 中使用，结束时撤销；不会写入文件、日志、参数或子进程环境。`target/acceptance/workspace-cli-TIMESTAMP/summary.json` 保存本次 fixture ID 与断言结果，回执只包含验收业务内容，不含凭据。失败也保留部分证据与 fixture，不宣称已清理。

覆盖 Markdown 与标签真实落库、稳定文档 ID 重放返回最新版本、异内容拒绝、标签 add/remove/clear 与 CAS 保护正文、本人/标签/创建日期分页、模板编辑与套用、文档归档恢复、任务及子任务、本人评论、关联文档、执行安排与截止分离、日小记原子关联、日视图、操作回执、全文检索；Java 另从 MySQL 验证小记 author/title 与无 AI 入队。

如需浏览器回读，可增加 `-HoldSeconds 300 -BrowserLogin`。CLI 完成后先撤销令牌，再保持后端有限时间，默认 0，最大 600 秒。打开 http://127.0.0.1:5235/api/auth/acceptance-cli-login 并点击真实 HTML 表单中的“登录验收成员”，建立固定验收成员的 15 分钟测试会话，随后正常进入小记 / 任务页面；GET 与 POST 均只接受回环来源，POST 还限制指定本机 Origin，使用独立 `WAO_CLI_ACCEPTANCE_SESSION` Cookie，不接受客户端指定账号。该 endpoint 仅显式启用的 test classpath 存在，生产 main 源码没有登录辅助。脚本从同一 env 在内存读取所需 Redis 变量供会话相关组件使用，不打印或传给 Node。验收窗口结束后服务关闭，临时 fixture 仍保留供人工核对。
