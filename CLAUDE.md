# Claude Code 项目指令

继承 `AGENTS.md` 中的所有规范。

- 修改代码前阅读 `docs/architecture.md` 和相关 Spec/ADR。
- 后端接口变化时同步更新 `docs/api/` 下的 OpenAPI 文件。
- 后端变更后运行 `mvn test`，涉及数据库时新增 Flyway migration。
- Service 公共方法提供 JavaDoc，核心逻辑提供单元测试。
