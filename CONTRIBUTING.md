# WAO 贡献指南

## 开始开发

```bash
git clone https://github.com/wayuwacn/wao.git
cd wao
git switch main
git pull --ff-only origin main
```

要求 JDK 17+、Maven 3.9+、Node.js 24+。无需 Docker 的本地 Demo 启动方式见根目录 `README.md`。

## Spec-Driven Development

实现业务代码前，先把可验收的规格提交到对应目录：

- 业务架构、模块边界和流程：`docs/business/`
- ER 图、表说明：`docs/database/`
- Flyway SQL：`wao-server/src/main/resources/db/migration/`
- OpenAPI 契约：`docs/api/`
- 页面交互和状态：`docs/pages/`
- 业务规则和验收场景：`docs/specs/`
- 重要技术决策：`docs/adr/`

设计图请优先提交可评审的 Mermaid、PlantUML、Markdown 或源文件，同时附一份 PNG/SVG 导出图。ER 图必须补充字段、主键、唯一约束、索引、关联关系和数据生命周期说明，不能只提交截图。

## 分支和提交

- 第一个正式版本上线前采用开发期快速交付：同步最新 `main`、完成本地验证后可直接提交并 push `main`，避免 PR CI 和 main CD 重复执行。
- 大型、冲突风险较高或需要隔离试验的变更可创建功能分支：`feat/`、`fix/`、`docs/`、`refactor/`、`test/`、`chore/`。
- 提交使用 Conventional Commits，例如 `feat(server): add user login API`。
- 提交信息除标题行外，请补充一段正文说明「做了什么、为什么」（哪怕只有一两句话）。CI 的飞书通知会把完整提交信息转发到团队群的「本次改动」卡片，只有标题的提交在通知里等于没写。这条约定对所有贡献者生效，不论是否使用 Claude Code；使用 Claude Code 的可参考 `.claude/skills/commit-message/`。
- 同一个业务模块或部署文件同一时间只由一个开发者修改；提交前再次 `git pull --ff-only origin main`，若主线已前进则先整合并重新验证。

## 提交前验证

```bash
mvn test
cd wao-web
npm ci
npm run lint
npm run test:unit -- --run
npm run build
```

API、页面或数据模型变化必须同步相应规格。本地验证是开发期主门禁；`main` push 后只执行一次部署与线上验收，不再重复运行一套 PR CI。详细策略和正式版前的恢复清单见 `AGENTS.md` 与 `docs/adr/011-development-fast-delivery.md`。

## 敏感信息

不得提交真实 `.env`、服务器密码、SSH 私钥、DNS Token、Webhook 或生产证书。部署环境使用节点私有 `deploy/.env` 和 GitHub Actions Secrets。
