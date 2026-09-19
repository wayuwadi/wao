# 文档草稿回归

## 运行前

先按 [本地开发](../../README.md#本机开发连接服务器-mysql) 启动连接既有数据库的 `local-cloud` 服务。使用本人正常登录会话和专用验收知识库。程序不创建账号、读取 Cookie、执行数据库迁移，也不替代登录。

测试分为两层：`npm run test:drafts` 是隔离单测，使用明确的测试数据；浏览器步骤使用真实服务和专用验收文档。单测通过不能替代浏览器结果。

```bash
cd wao-web
npm ci
npm run test:acceptance-tools
npm run test:drafts
WAO_ACCEPTANCE_API=http://127.0.0.1:18080 npm run dev:acceptance
```

另开终端启动第二个源，模拟另一窗口并隔离 localStorage / IndexedDB：

```bash
cd wao-web
WAO_ACCEPTANCE_API=http://127.0.0.1:18080 WAO_ACCEPTANCE_PORT=5232 npm run dev:acceptance
```

默认第一入口为 `http://127.0.0.1:5231`。业务服务和前端均只允许本地源。第二入口默认没有故障。日志显示故障配置的实际路径；可用 `WAO_ACCEPTANCE_FAULT_FILE` 指向仓库外独立文件。不要把两个入口指向同一故障文件。

## 浏览器回归

供有 Codex 浏览器工具的任务使用。先通过 `cua.getState()` / `cua.createBrowserTab()` 确认浏览器和两个页签，按工具文档读取浏览器使用规范。模块需要带 `playwright`、`screenshot` 的 Tab：从工具文档提供的 `agent.browsers.get(实际浏览器ID)` 和 `browser.tabs.get(实际页签ID)` 获取，不能将只有 AX 方法的包装对象直接传入。通过页面确认已登录、专用知识库的名称及 `/kb/...` 地址；不要使用示例中的占位值直接执行。

在 `cua_repl` 中导入仓库内的模块，使用实际绝对路径：

```javascript
var { DocumentDraftRegression } = await import('/absolute/path/wao/wao-web/scripts/acceptance/document-drafts.mjs')
var run = new DocumentDraftRegression(tab, {
  baseURL: 'http://127.0.0.1:5231',
  kbPath: '/kb/已核实的验收知识库ID',
  kbName: '已核实的验收知识库名称',
  // 自定义故障路径时必须与第一入口使用同一文件。
})
```

页签切换前读取当前实际页签名称，返回后核对文档地址，兼容冷启动时的临时名称。按钮与菜单项通过 Enter 激活，沿用真实控件的键盘语义；不执行 DOM 事件脚本或修改应用内部状态。按顺序逐项调用，每步返回结果后查看现场和进度；不要把整个流程塞进一个长时间调用，单个步骤的工具时限应留足页面刷新时间（建议 50 秒）：

```javascript
await run.create()
await run.saveAndReload()
await run.failureAndTabSwitch()
await run.reloadAndRetry()
await run.prepareConflict(peerTab, 'http://127.0.0.1:5232')
await run.saveConflictCopy()
// 保存与冲突验证完成后，按下节清理本轮文档。
```

任何一步失败，立即 `await run.finish(false, error)`：清除故障开关，保存失败截图和已通过的步骤，保留本轮文档供诊断。不得跳过失败步骤后标成全通过。重跑新建独立实例；不要重复调用旧实例的 `create()`。

## 清理本轮文档

在仓库根目录先运行只读预检，确认输出的目标数与本轮报告一致，再增加 `--apply`：

```bash
python3 deploy/scripts/cleanup-draft-acceptance.py /实际本机路径/report.json --env-file .env.local-cloud
python3 deploy/scripts/cleanup-draft-acceptance.py /实际本机路径/report.json --env-file .env.local-cloud --apply
```

可用 `--java-home`、`--maven-bin` 指定已有运行时。该命令不启动 Spring、Flyway 或后台任务，只读取报告中最多两篇文档，核对精确 ID、标题、知识库、创建时间及非自动生成属性。报告超过 24 小时、任一目标不符时停止；执行模式先锁定全部目标并核对，再通过现有 `KnowledgeService.deleteDoc` 软删除，失败回滚。已经进入回收站的目标不会重复删除，维护清理的删除人留空。不要改写报告来清理其他文档。

随后通过浏览器刷新目录核实，并完成报告：

```javascript
await run.verifyCleanup()
await run.finish(true)
```

也可在正常页面按报告精确 ID / 标题把本轮文档移入回收站，再调用 `verifyCleanup()`。知识库首页分组需要全部展开；标题数量与文档总数不一致时不会确认清理。未核实清理前报告保留 `cleaned: false`，不得用清空回收站替代。验收入口不修改应用的删除确认框；原生弹窗阻塞浏览器工具时，人工取消后再继续交互。

浏览器工具会话重置时，可以恢复已有报告继续诊断或清理，不重复创建文档：

```javascript
var run = DocumentDraftRegression.resume(tab, {
  reportFile: '/本机已生成的绝对路径/report.json',
  kbName: '本轮已核实的验收知识库名称', peerTab,
  // 若此前自定义 faultFile，必须提供原来的同一路径。
})
```

恢复不代表失败步骤已经通过；按报告核对最后完成的位置。副本已经通过页面创建，但通知消失导致旧脚本停下时，可用 `verifyConflictCopy()` 核实已有副本，不能再点一次另存。全部 11 项独立步骤和两篇文档清理都确认后，`finish(true)` 才能生成通过报告。

## 证据与边界

- `report.json` 和各步骤 PNG 默认保存在系统临时目录的 `wao-draft-regression/<runId>`。文件为本机私有，目录拒绝指向当前仓库；不把原始截图、浏览器状态或报告加入 Git。
- 故障默认关闭，只作用于本轮明确文档 ID 的 PATCH，十分钟后自动失效；GET、协同心跳、其他文档和删除请求透传。只模拟浏览器到本地服务的保存故障，不冒充生产故障演练。
- 正文通过可见编辑器精确比较，成功后刷新核实；不靠 Pinia 内部变量、接口状态码或“保存成功”提示单独证明持久化。
- 清理只软删除本轮记录的精确文档，标题不一致就停止。报告里保留每篇文档的清理结果。
- 测试覆盖正常保存、延迟、失败、刷新恢复、页签切换和两窗口冲突。浏览器崩溃、操作系统断电及跨账号草稿迁移不在本包的完成声明内。
