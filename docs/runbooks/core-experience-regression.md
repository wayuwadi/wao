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
