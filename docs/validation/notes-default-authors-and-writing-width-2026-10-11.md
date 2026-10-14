# 小记默认作者与记录区宽度验证

范围：默认全部作者、旧偏好迁移、记录区自适应默认宽度及按钮文字保持一行。规格见 `../specs/notes-default-authors-and-writing-width-2026-10-11.md`。

## 已验证

- `npm run test:unit -- --run src/__tests__/NotesView.spec.ts`：108/108 通过。包含全部作者、本人和指定作者切换、清除筛选、v2 迁移保留其他条件、v3 刷新恢复、账号隔离、延迟登录、默认宽度适配、手动宽度、恢复默认、拖拽清理及原有草稿回归。
- `npm run build`：类型检查和 Vite 生产构建通过，保留已有大块体积提示。首次运行缺少相邻游戏宿主的 Vue 依赖；使用当前锁文件匹配的既有依赖后重新通过，未改变依赖清单。
- `npm run ui:check -- --base origin/main`：通过，沿用既有主题变量和编辑器组件，仅修改小记记录区布局。
- `git diff --check`：通过。
- `python3 deploy/scripts/check-engineering-consistency.py --base origin/main`：0 错误，5 个已有 ADR 重复编号警告。
- 独立本地预览 `/notes` 返回 HTTP 200，API 复用既有 5174 本地环境的代理，不创建数据库或业务数据。

## 缺项与交付边界

- 当前工具的内置浏览器返回 `Browser is not available: iab`；可用浏览器清单仅有用户 Chrome/Edge，按用户偏好不打开外部浏览器。Codex 应用本身也被工具访问限制阻止。
- 尚未完成真实登录数据页面中底部按钮同排、明暗主题、320/390px 与键盘的视觉验收；单元测试和构建不能替代这些检查。
- 保留草稿 PR 等待本地浏览器验收，不合并、不发布，不宣称已上线。
