# 四功能核心体验回归

## 任务收尾一致性

主任务已完成且所有内嵌子项都已完成才算收尾。待验证继续保留；独立子任务实体分别判断。进入待办列表、看板或四象限，可以看到「仍有 X 项子项未收尾」，直接打开任务继续处理。日历关闭「显示已完成任务」后，仍保留这些任务；主状态不会被自动修改。

在 `wao-web` 执行：

```powershell
npm.cmd run test:unit -- --run src/__tests__/TodoView.spec.ts src/__tests__/CalendarView.spec.ts src/__tests__/projectCompletion.spec.ts src/__tests__/projectTaskFocus.spec.ts src/__tests__/completion.spec.ts src/__tests__/taskQuadrants.spec.ts --maxWorkers=2
npm.cmd run type-check
npm.cmd run build-only
```

真实验收仅操作专用验收记录，精确标识、截图和持久化读取结果放忽略目录 `.local/core-acceptance/`。复用正常登录和既有授权数据库。

1. 主任务完成、两层内嵌子项中下级待验证，设置一个可见日历日期。项目四视图、待办三视图、日历月/周/日均应保留提示与打开入口；月视图溢出项可由「还有」打开。
2. 日历隐藏已完成，确认任务仍在。打开详情确认主状态仍为已完成、子项待验证。
3. 完成最后子项后任务收起。待办展开完成组，重新打开主任务，刷新确认主状态和子项分别持久化。
4. 390 像素真实页面确认提示可读，用 Enter 打开任务；核对长标题和子项入口。
5. 完成缓冲、失败重试、成员筛选和独立子任务边界由上述自动化覆盖。不要将模拟失败写成真实网络故障实测。

2026-10-03：上述真实操作已通过；51 项自动化、类型检查及构建通过。真实数据使用用户授权的 Windows1 临时环境。完成后将专用主任务保留为主完成、子项待验证，便于复看。未执行部署后的业务验收，本轮合并即交付。

## 执行安排与截止

新建任务的「时间安排」中，截止日期与执行安排分别填写。执行可以不安排、只指定日期或填写时段。已有任务打开「更多属性」可编辑执行日期/时段，也可「清除执行安排」；截止仍在上方独立修改。日历拖动只改执行安排，保存成功后可撤销；任务有后续更新时不可撤销，服务器冲突也不会覆盖其他窗口内容。

在 `wao-web` 执行：

```powershell
npm.cmd run test:unit -- --run src/__tests__/taskSchedule.spec.ts src/__tests__/CalendarView.spec.ts src/__tests__/TaskDrawer.spec.ts src/__tests__/newTaskPersistence.spec.ts src/__tests__/taskWriteReceipt.spec.ts --maxWorkers=2
node --test scripts/acceptance/faults.node.mjs
npm.cmd run type-check
npm.cmd run build-only
```

真实回归：

1. 专用任务设周三 23:00 至周四 02:00 执行、周五截止。拖到周四后保持三小时、截止周五，刷新后核对；日历跨日续段与同日截止只显示一条。
2. 在本地验收代理的故障文件填 `taskId`、`mode: fail`、五分钟内的 `expiresAt`，仅拒绝该任务 PUT/幂等写入。确认失败保留安排且截止不变，写空对象关闭故障后重试，再撤销。
3. 再次调整安排，从另一正常会话页面修改标题并保存。旧页撤销应隐藏或被 409 拒绝，服务器标题和安排不回退；任务详情可查看服务器日期与正文。仅在专用记录上处理冲突草稿。
4. 清除执行安排、刷新，截止仍在；设置执行日期但清空截止，月/周/日均显示「执行（未指定时间）」。再次刷新确认未生成任意时刻。
5. 390 像素页面核对长任务入口与日期输入，可用键盘打开任务并切换输入焦点。

2026-10-03：91 项回归、6 项故障工具测试、类型和构建通过。上述真实链路通过。浏览器工具在一个原生确认框上超时，保留该页冲突草稿；另一个独立本地源地址通过正常登录 Cookie 读取同一真实后端，完成清空/刷新和窄屏核验，不将本机冲突草稿误当服务器状态。具体标识、持久化读取和截图保留在忽略目录。

## 小记与文档正文检索

点击侧栏「搜索功能与内容」或 Ctrl/⌘K，输入正文里的关键词。结果显示小记/文档类型、知识库和命中片段；点击打开并高亮定位。检索已保存内容，不含归档/回收站。正在编辑的草稿保持原样，若其已删除关键词，会提示当前内容未找到。小记清单搜索也提供「定位」，清除后恢复搜索前列表位置、分页与展开。

在 `wao-web` 执行：

```powershell
npm.cmd run test:unit -- --run src/__tests__/contentSearch.spec.ts src/__tests__/GlobalSearchNavigation.spec.ts src/__tests__/NotesView.spec.ts src/__tests__/MarkdownEditor.spec.ts src/__tests__/KbDetailView.spec.ts --maxWorkers=2
npm.cmd run type-check
npm.cmd run build
```

真实回归：

1. 建立专用普通文档及小记；正文放同一独有关键词，文档使用长正文；再建两条含关键词的专用内容，分别归档、移入回收站。记录精确 ID，关闭小记自动 AI 操作。
2. 全局查找应仅返回两条活动内容。分别点击正文、标题命中，确认来源、片段、选区及滚动位置；清除小记页查找恢复列表。
3. 在专用文档本地代理故障文件填写其 `docId`、`mode: fail` 和短有效期。编辑正文删除原关键词，搜索并再次进入同一结果，草稿必须保留且提示当前未找到。写空对象解除故障，保存重试并刷新。
4. 小记保持编辑草稿，打开全局查找、定位同一记录；确认未自动提交也未覆盖。手动保存后刷新仍存在。
5. 390像素下检查片段换行、定位高亮、下一个和清除按钮。组合输入用组件 compositionstart/end + Enter 回归；测试多页结果、迟到成员响应、加载/失败/重试。

2026-10-03：真实 Windows1 正常登录链路已通过上述正文/标题、2906字长文档、多结果、归档/回收站、文档503草稿、小记草稿、持久化及390像素操作。未登录完整正文接口返回401。156项定向回归通过，成员切换丢弃迟到响应与目录外内容排除有自动化覆盖。没有模拟私有成员ACL已存在；当前后端共享知识库范围的权限承诺对齐保留为后续事项。截图与专用ID保存在忽略目录 `.local/core-acceptance/search-*`。

## 待办截止分组

打开「待办 → 列表」查看逾期、今天到期、本周到期、以后到期、无截止日期。空组不显示；已完成仍收起。任务的执行安排单独提示，今天执行但未来截止仍留在未来截止组。负责人、参与人、等待他人和项目筛选继续组合使用。

```powershell
cd wao-web
npm.cmd run test:unit -- --run src/__tests__/todoDueGroups.spec.ts src/__tests__/TodoView.spec.ts src/__tests__/taskQuadrants.spec.ts src/__tests__/taskSchedule.spec.ts src/__tests__/deco.spec.ts --maxWorkers=2
npm.cmd run build
```

真实验收为专用项目 `p1791000623315`、任务 #23—27：五种日期分别归组，#23逾期且等待反馈，#26未来截止今天执行，#27无截止今天执行。等待+项目得到准确单项、切到未关联项目显示筛选空结果；恢复全部后五组恢复，列表/看板/四象限均显示执行提示。390像素下可阅读分组并键盘打开#27。35项自动化覆盖周末、跨周、跨月跨年、午夜时段边界、已完成折叠与组合筛选。数据为Windows1真实持久化，未改用户原任务。

同步最新主线后新增的 `wao-game` 包需在独立工作区运行 `npm.cmd ci --prefix wao-game --ignore-scripts` 安装锁定依赖，供主站类型检查解析共享游戏宿主；无需修改依赖版本。

## 项目快捷条件

在项目任务工具栏下点击「待验证 / 等待反馈 / 逾期」。按钮数字是项目任务实体总计；人员、创建人、查找继续取交集，提示另列当前筛选数量。「全部」仅清快捷条件；关键词结果中的「清除筛选」清人员及快捷条件，保留关键词。

```powershell
cd wao-web
npm.cmd run test:unit -- --run src/__tests__/projectActionFilters.spec.ts src/__tests__/projectTaskSearch.spec.ts src/__tests__/ProjectDetailLoading.spec.ts src/__tests__/ProjectDisplaySettings.spec.ts src/__tests__/projectCompletion.spec.ts --maxWorkers=2
npm.cmd run build
```

真实回归使用项目 `p1791000623315` 的 #23—27：项目计数待验证1、等待反馈1、逾期1；切待验证后搜索#23标题显示被快捷条件排除，清条件恢复1条命中。负责人切到无命中成员显示筛选内0但项目计数仍为1；切回并组合创建人恢复真实结果。项目 `p1790990344904` 的#17主已完成、#19子项待验证仍能命中；任务状态未改变。对子项折叠并按标题排序，切快捷条件后清除，两者仍保留。390像素可点击切换，不溢出工具栏。

2026-10-03：21项定向回归、类型和构建通过；加载/失败禁用伪计数与重试、任务不被改写、个人顺序/排序/折叠保留已自动化验证，真实浏览器完成以上链路。


## 四页视觉回归（2026-10-03）

在 `wao-web` 执行：

```powershell
npm.cmd run test:unit -- --run src/__tests__/NotesView.spec.ts src/__tests__/TodoView.spec.ts src/__tests__/todoDueGroups.spec.ts src/__tests__/projectActionFilters.spec.ts src/__tests__/ProjectDisplaySettings.spec.ts src/__tests__/projectCompletion.spec.ts src/__tests__/CalendarView.spec.ts src/__tests__/CalendarDayPlan.spec.ts src/__tests__/taskSchedule.spec.ts --maxWorkers=2
npm.cmd run build
```

真实链路只使用登记的专用内容：

1. 小记：搜索专用关键词，点击常显「继续编辑」。同时保留左侧新草稿与右侧编辑；仅对专用记录注入保存失败，确认两份输入保留，解除故障后重试并刷新。390像素保存按钮44像素。
2. 待办：专用项目 #23—27 保留五种截止分组，标题与执行/等待提示分层。切等待筛选再恢复，手机「更多」和Esc可用。
3. 项目：专用项目 #17父已完成、#19子待验证继续显示未收尾提示；先折叠，再切待验证并清除，折叠不丢。#20保留专用长标题测试两行截断；手机父标题不被辅助标记挤掉，快捷条件40像素。
4. 日历：月/日视图区分紫色执行实线与浅橙截止虚线；点击专用任务打开详情。辅助区展开/收起不改偏好；收起时天气设置弹窗可打开。手机 Enter 展开/收起，横滚到周末；已完成开关和日期导航仍可用。

本轮各页分别通过52、19、9、20项定向回归，项目与新窄屏导航合验16项。真实浏览器截图、故障注入日志及精确标识在忽略目录 `.local/core-acceptance/visual-*`；不提交凭据。线上只核对版本、资源与健康，业务体验由用户验证。阶段状态见 `docs/validation/core-visual-progress-2026-10-03.md`。
