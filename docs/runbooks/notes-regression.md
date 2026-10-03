# 小记真实浏览器回归

验证“小记单份最新正文”已保存内容和本机草稿。沿用[文档草稿回归](document-draft-regression.md)的本地故障代理；不创建数据库、不执行迁移、不读浏览器 Cookie，不模拟业务接口成功。

## 启动

先确认现有真实服务与数据库。2026-10-02 本机公网入口使用既有临时开发库，不能声称已恢复旧生产库。使用专用验收账号或本人正常登录会话，两个专用本地源，避免和其他工作抢端口。以下为 PowerShell 示例，API 端口按本机实际配置调整：

```powershell
cd wao-web
npm ci
npm run test:acceptance-tools
$env:WAO_ACCEPTANCE_API='http://127.0.0.1:18080'
$env:WAO_ACCEPTANCE_PORT='5241'
npm run dev:acceptance
```

另一个终端同样启动，端口改为 `5242`。不要共用故障文件；默认文件按端口区分，单篇文档 PATCH 故障十分钟后失效。用浏览器分别打开两端的 `/notes` 并完成正常登录。模块会清空这两个专用源的小记筛选，但不会覆盖新建区已有输入；如有草稿立即停止。

## 执行与判据

在 Codex 浏览器工具中读取当前浏览器文档，用提供 `playwright`、`screenshot` 的原生 Tab 句柄。将下面路径换为实际工作区的绝对路径；Windows 使用 `file:///C:/...` 导入。

```javascript
var { NotesRegression } = await import('file:///实际工作区/wao-web/scripts/acceptance/notes.mjs')
var run = new NotesRegression(tab, peerTab, {
  baseURL: 'http://127.0.0.1:5241', peerURL: 'http://127.0.0.1:5242',
})
await run.create()
await run.consecutiveSaves()
await run.failureRecovery()
await run.competingWrites()
await run.savingProtection()
// 另独立打开同源同账号的 /notes 页签，执行页签隔离专项：
await run.sameOriginDrafts(sameOriginTab)
```

逐项执行、观察结果，单次工具调用留足约 50 秒。任何实际步骤失败调用 `await run.finish(false, error)`，清除故障并保存失败报告；重新验证创建新实例，不把旧失败报告改成通过。不要重复提交已在服务端成功但观察超时的操作。

- 新建后从独立源刷新比较完整正文，连续两次编辑保存后再次打开检查。
- 注入明确的 503 失败，核实原因、输入、收起预览、刷新恢复，再恢复连接重试并独立源读取。
- 第二源保存不同正文和标题，第一源继续输入不受刷新覆盖；第一源提交后，其正文和未手动修改的远端标题共同持久化。
- 延迟保存时验证保存/收起按钮禁用，完成后独立源读取。重复请求幂等仍需服务端测试，不能凭按钮禁用声称网络重放安全。

**保存按钮从“保存修改”变成“正在保存”时，原标签已经消失，但请求尚未完成。** 工具等待标题编辑框关闭才继续；只在本源刷新也不够，本地恢复草稿会使失败写入看起来已持久化，必须用独立源核实。

## 窄屏与清理

通过浏览器文档的 `viewport` 能力设置 390×844，再调用 `await run.narrowFailure()`，完成后 `reset()`。工具会读取实际 DOM 宽度，不接受设置成功但页面仍是桌面宽度的假结果。当前 Edge 扩展实测视口覆盖未生效；内置浏览器可有效设置 390px。必要时在已登录的内置浏览器打开同一本地源，将 `run.tab` 绑定为该 Tab，完成窄屏后还原原 Tab。切换浏览器前确认该小记正文没有未保存输入。

该步骤检查失败原因、重试按钮横向边界并保存截图，重试后仍从独立源确认。截图还需人工核对遮挡、换行和阅读顺序，不能只靠几何数值宣布布局合格。

按报告 `docId` 找到本轮合成小记，核对正文中的 `runId`，在卡片右键菜单选择“移入回收站”并确认。只清理本轮精确 ID，不清空回收站，不凭相似标题删除旧记录。然后：

```javascript
await run.verifyCleanup()
await run.finish(true)
```

清理检查会去掉筛选，再双源刷新查找精确小记；加载失败不能通过。全部 7 类步骤和清理成立才生成通过报告。浏览器会话切换后使用 `NotesRegression.resume(tab, peerTab, { reportFile: '/实际路径/report.json' })` 接续，定制过故障文件时同时提供原 `faultFile`。

## 证据与未覆盖边界

- 原始报告与 PNG 默认在仓库外系统临时目录 `wao-notes-regression/<runId>/`，报告带基线 SHA、工作区是否有改动、步骤与清理状态。需长期保留时复制到受控本机私有目录；Windows 的文件 `mode` 不等于 NTFS ACL。
- 只提交脱敏结论，不提交截图、精确业务 ID、会话、凭据或真实私有正文。合成内容带“合成验收”标记。
- 基础 7 类步骤之外，`sameOriginDrafts` 额外验证同源独立页签各自刷新恢复、一方保存不清除另一方，以及第二页签失败恢复。修改页签草稿时必须执行此专项并检查报告中的专项通过项；仅基础套件通过不代表专项已执行。浏览器“复制页签”会复制 sessionStorage，不在独立打开页签的通过声明内。迟到 AI 的实际生成、光标位置、真实 Runner 重启及服务端重复回调也需要组件/队列测试和可用执行环境。
- 配置了真实 AI 的账号保存可能按已有偏好入队；确认预算与环境再运行。工具不切换模型，不配置供应商或付费 API，不修改个人 AI 偏好。
- 不修改用户已确认的小记单份正文体验，也不新增业务 API、CLI 或 MCP 权限。

## 复制会话专项

自动回归（在 `wao-web` 下）：

```powershell
npm run test:unit -- --run src/__tests__/noteDraftStorage.spec.ts src/__tests__/autoNoteEnrichment.spec.ts src/__tests__/NotesView.spec.ts src/__tests__/QuickNoteModal.spec.ts --maxWorkers=2
```

以下为仍需真实浏览器操作的步骤，不把单元测试当作真实链路：

1. 按上文启动一个 `dev:acceptance` 入口，登录既有服务，创建并登记专用合成小记，编辑一份未保存草稿。
2. 在该页签打开同源 `/_acceptance/clone-notes`，确认身份准备成功，点击“打开复制页签”。该按钮通过真实 `window.open` 继承会话存储，不模拟业务成功。
3. 原页签点击“返回原页签小记”，确认两边均有复制前草稿。两边分别输入 A、B，刷新后仍各自恢复 A、B。
4. 原页签保存 A，复制页签刷新仍保留待保存 B；复制页签保存 B 后刷新，显示服务器最新 B。显式保存覆盖正文遵循单份最新内容规格。
5. 保存截图与精确标识，按上文软删除合成小记并刷新确认。不要删除其他页签或账号草稿。

辅助页面只由本地验收服务器提供；普通 Vite 构建不包含该页面。该路径验证浏览器 opener 的会话复制行为，不能宣称已操作所有浏览器的原生复制菜单。刷新恢复沿用已有身份，不创建迁移副本；混用未刷新的旧代码页签不在新锁保证内。
