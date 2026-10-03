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
