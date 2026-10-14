# WAO 开发约定

唯一维护仓库是 `https://github.com/wayuwadi/wao.git`。当前只维护浏览器产品；历史客户端源码和标签用于追溯，不默认恢复原生发布。

先阅读 `README.md`、`docs/architecture.md` 和相关当前规范，再修改代码。旧方案在 Git 历史中查看，不据此操作生产。

- 使用 Vue 3、TypeScript、Spring Boot 3、Java 17、MyBatis-Plus、MySQL 9 和 Flyway，不擅自更换技术栈。
- Java 包名 `cn.wayuwa.wao.*`；响应统一为 `ApiResponse<T>`；前端 API 集中在 `wao-web/src/api/`。
- 新需求或 BUG 使用独立 PR，基于最新 `main`；中文 Conventional Commits，正文包含“改动：”“原因：”“验收：”。
- Flyway 新增唯一版本，不修改已交付迁移，不关闭校验或运行 `repair` 隐藏错误。原生产的兼容资源由服务器私有配置管理。
- 不提交真实 `.env`、Token、Webhook、OAuth 会话、私钥、备份、日志、运行数据或个人截图；测试使用虚构数据。
- 不重置用户数据库，不删除上传文件或修改密码库密钥。发布前确认环境、备份、版本和回退路径；公开仓库不授予生产操作权限。
- 生产只由维护者 Jenkins 从受信任 `main` 发布。Fork/PR 检查不接触生产凭据，不执行部署。
- 不自动领取或关闭用户任务，不发送消息；这些操作需要当前用户授权。
- HTTP API、工作区 MCP 和 CLI 能力说明随功能同步，不把新能力自动授予旧令牌。
- 界面沿用 `docs/design/wao-ui-system.md`、现有主题 token 和公共组件；设置布局遵循 `docs/design/settings-ui-pattern.md`。
- 编辑功能覆盖草稿、保存失败和并发修改；按需求完成真实链路验收，不把“合并”报告为“已上线”。

提交前运行相称测试。Windows 发布输入测试：`pwsh -NoProfile -File tests/deployment/WindowsBuildInputs.Tests.ps1`。页面修改运行类型检查、相关 Vitest 和 UI 规范检查。
