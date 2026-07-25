# WAO 贡献指南

## 开始开发

```bash
git clone https://github.com/wayuwacn/wao.git
cd wao
git switch main
git pull --ff-only
git switch -c feat/<scope>-<description>
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

- 禁止直接推送 `main`；所有日常变更通过 Pull Request 合并。
- 分支：`feat/`、`fix/`、`docs/`、`refactor/`、`test/`、`chore/`。
- 提交使用 Conventional Commits，例如 `feat(server): add user login API`。
- 提交信息除标题行外，请补充一段正文说明「做了什么、为什么」（哪怕只有一两句话）。CI 的飞书通知会把完整提交信息转发到团队群的「本次改动」卡片，只有标题的提交在通知里等于没写。这条约定对所有贡献者生效，不论是否使用 Claude Code；使用 Claude Code 的可参考 `.claude/skills/commit-message/`。
- 同一个业务模块或部署文件同一时间只由一个分支修改。

## 提交前验证

```bash
mvn test
cd wao-web
npm ci
npm run lint
npm run test:unit -- --run
npm run build
```

API、页面或数据模型变化必须同步相应规格。CI 通过、CODEOWNERS Review 完成后再 Squash Merge。

> **开发测试阶段临时策略**：距离正式上线尚早，管理员已授权 AI 代理在 CI（backend/frontend/containers）全部通过后直接 Squash Merge、无需等待人工 Review（仍然通过 PR，不是直接推送 `main`）。飞书通知也相应调整为只在合并到 `main` 后触发一次，不再对每次开发分支提交推送。临近正式上线前需要移除这条例外，恢复上面的人工 Review 要求，见 `AGENTS.md`「合并策略（开发测试阶段，临时）」。

## 敏感信息

不得提交真实 `.env`、服务器密码、SSH 私钥、DNS Token、Webhook 或生产证书。部署环境使用节点私有 `deploy/.env` 和 GitHub Actions Secrets。
