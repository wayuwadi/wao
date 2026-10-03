# 项目能力回归与使用

## 任务收尾计数

项目列表卡片展示未收尾任务数；点击项目进入默认表格。主任务已完成而子项未完成时，行内说明剩余子项，不必改变主任务状态。页签始终是整个项目的计数，负责人/创建人筛选下的计数在筛选提示中查看。卡片另列内嵌子项，不能把两种数量相加。

从仓库根目录执行：

```powershell
cd wao-web
npm.cmd run test:unit -- --run src/__tests__/projectCompletion.spec.ts src/__tests__/projectTaskFocus.spec.ts src/__tests__/projectSpaceRefine.spec.ts src/__tests__/projectListLayout.spec.ts src/__tests__/ProjectsLoading.spec.ts --maxWorkers=2
npm.cmd run type-check
npm.cmd run build-only
```

本地真实链路：复用当前授权数据库与正常会话，独立 Vite 源代理已有业务服务。仅操作本轮创建的私有验收项目和任务，记录项目/任务/子项精确 ID、版本、日期及操作结果到 Git 忽略的 `.local/projects-acceptance/`，不把真实标识或截图提交到仓库。

1. 创建专用项目，列表显示「暂无任务」、任务页签 0。
2. 创建一条主任务、两层子项；将主任务和上级子项标为已完成，下级设为待验证。卡片/页签均为未收尾 1，表格保留该行并标示剩余子项。
3. 切换表格、分组、看板、四象限，未收尾任务均可打开。主状态一直是已完成。
4. 再建待验证任务，核对任务实体和内嵌子项分开计数；切换负责人/创建人筛选，页签不变化、筛选范围明确。
5. 完成所有子项和主任务，经过原有完成缓冲后收起；打开「显示已完成」可见历史，卡片为未收尾 0。
6. 整页刷新确认真实保存结果；窄屏下读取卡片、状态和筛选提示。加载失败不等于零任务。

先以隔离单测覆盖异常和边界，再记录真实页面实际通过/未验证项。构建、接口成功或模拟页面不能替代上述链路。

## 截止日期

新项目默认未排期。进入项目右上角「更多操作 → 项目设置」，在名称下填写截止日期，或点击「清空日期」恢复未排期；保存后重新打开设置和刷新页面核对。历史项目日期保持不变。

前端：`npm.cmd run test:unit -- --run src/__tests__/ProjectSettingsModal.spec.ts src/__tests__/projectNotify.spec.ts src/__tests__/projectListLayout.spec.ts`；CLI：`npm.cmd --prefix wao-cli test`；后端：`mvn -pl wao-server -am test -Dtest=ProjectServiceTest,WorkspaceMcpToolsTest,WorkspaceMcpControllerTest,FlywayMigrationsTest -Dsurefire.failIfNoSpecifiedTests=false`。

真实验收需启动本轮后端、关闭迁移及共享后台任务，复用既有数据库；验证新建无日期、历史日期不变、填写/修改/清空后刷新。使用 `wao-web/scripts/acceptance/serve.mjs` 时，可在它输出的本地故障文件写入 `{ "projectId": "本轮专用项目ID", "mode": "fail", "expiresAt": 未来五分钟内毫秒时间戳 }`，仅拒绝该项目的 PATCH，不影响其他页面或服务器。验证失败保留日期输入后清除文件并重试；禁止对业务项目注入故障。`node --test scripts/acceptance/faults.node.mjs` 验证隔离边界。
