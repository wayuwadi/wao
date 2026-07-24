# Codex 项目指令

继承 `AGENTS.md` 中的所有规范。

- 修改代码前阅读 `docs/architecture.md` 和相关 Spec/ADR。
- 新增页面时同步更新 `wao-web/src/router/index.ts`。
- API 调用统一封装在 `wao-web/src/api/`。
- 前端变更后运行 `npm run lint`、`npm run test:unit -- --run` 和 `npm run build`。
