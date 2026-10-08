# 待办展开清单验收记录

## 已执行

- 从最新 `origin/main` 建立独立工作区，未包含另一个任务详情需求的改动。
- `TodoDayView`、`TodoTaskChecklist`、`TodoView`、`DesignReferenceView`、`todoOperationReceipt` 共 5 个测试文件、36 项测试通过。覆盖未排期进展可见、待验证子项保留、历史快照只读、未来禁写、更多菜单、草稿隔离、日期切换、个人顺序与历史稳定键、错误反馈与原有保存回执。
- `node node_modules/vue-tsc/bin/vue-tsc.js --build` 通过。
- `node node_modules/eslint/bin/eslint.js` 对本次修改的 5 个 Vue / 测试文件检查通过。
- `node scripts/check-ui-system.mjs --base origin/main` 通过，等价于项目的 `npm run ui:check -- --base origin/main`。
- `node node_modules/vite/bin/vite.js build` 通过，保留既有大块制品警告。
- 工程一致性检查无错误；仅有仓库历史 ADR 重号警告，本需求没有新增 ADR 或迁移。
- 在本地 `/design` 使用实际 `TodoTaskChecklist` 与 `TodoDayLayout` 检查浅色／深色、桌面与 390／320px、勾选、展开已完成子事项。发现并修复 320px 下完成状态的基线对齐溢出，复查页面宽度与内容宽度均为 320px。设计参考明确标为示例，没有业务写入。

## 尚未执行

本机未找到 WAO 的既有私有数据库连接配置，未启动已连接真实数据库的本地业务服务。因此尚未完成本人登录后的任务／子事项真实写入、刷新读回、历史数据与进展草稿的真实用户链路验收；设计参考和自动测试不替代这一项。

已向用户询问既有私有配置路径，不索取密码或 Token 明文。交付保留为草稿 PR；按项目规则，补齐真实链路验收前不合并、不发布。公网版本核对与实体设备最终体验也未执行。
