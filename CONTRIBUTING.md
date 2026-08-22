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

- 第一个正式版本上线前采用开发期快速交付，交付单元是 **一个需求或一个 BUG 一个 PR**：同步最新 `main` → 切功能分支 → 本地验证 → push → 建 PR → Squash Merge 并删除源分支。多条需求不合并进同一个 PR，一条需求也不拆成多个 PR。
- 分支前缀：`feat/`、`fix/`、`docs/`、`refactor/`、`test/`、`chore/`。
- PR 不承担重复 CI 门禁：CI/CD 只在 `main` 的 push 上跑，PR 页面没有 checks 是预期行为；合并前以本地验证全绿为准。
- 提交使用 Conventional Commits，保留 `type(scope):` 前缀，描述使用中文，例如 `feat(server): 增加用户登录接口`。
- 正文使用中文并固定包含“改动：”“原因：”“验收：”三个字段；代码标识、API 路径、命令和专有名词可保留原文。CI 的飞书通知会把完整提交信息转发到团队群的「本次改动」卡片。这条约定对所有贡献者生效，不论使用哪种 AI；使用 Claude Code 的可参考 `.claude/skills/commit-message/`。
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

API、页面或数据模型变化必须同步相应规格。本地验证是开发期主门禁；`main` push 后只执行一次部署与线上验收，不再重复运行一套 PR CI。详细策略和正式版前的恢复清单见 `AGENTS.md`、`docs/adr/011-development-fast-delivery.md` 与 `docs/adr/014-one-pr-per-change.md`。

push 前除了文本冲突，还要核对 git 不会报的**语义冲突**：两边各自新建了同编号的 ADR 或同版本号的 Flyway migration 时，文件名不同所以合并静默通过，但语义上撞车，必须重新编号并同步所有引用。

## 敏感信息

不得提交真实 `.env`、服务器密码、SSH 私钥、DNS Token、Webhook 或生产证书。部署环境使用节点私有 `deploy/.env`、`deploy/.env.secrets` 和 Jenkins Credential Store。
