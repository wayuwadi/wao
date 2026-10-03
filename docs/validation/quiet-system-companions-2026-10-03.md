# 安静伙伴回归验证

## 自动化

在 `wao-web` 运行：

```powershell
npm exec -- vitest run src/__tests__/quietCompanions.spec.ts src/__tests__/taskCompanionConfirmation.spec.ts src/__tests__/companionPortrait.spec.ts src/__tests__/companionSpace.spec.ts src/__tests__/homeCompanions.spec.ts src/__tests__/companionScene.spec.ts src/__tests__/VersionUpdateNotice.spec.ts src/__tests__/GlobalToolsMenu.spec.ts src/__tests__/newTaskPersistence.spec.ts src/__tests__/NotesView.spec.ts src/__tests__/ProjectsLoading.spec.ts src/__tests__/KnowledgeLibraryDiscovery.spec.ts --maxWorkers=2
npm run type-check
npm run build-only
```

宿主焦点、Escape 与切页检查：`npm exec -- vitest run src/__tests__/quietCompanionHost.spec.ts`。

本轮结果：13 个测试文件共 117 项通过，类型检查与正式前端构建通过。工程一致性检查无错误，保留基线已有 5 组 ADR 重号警告，未新增 ADR 或迁移。构建仍有既有大分包与动态导入告警，未因本轮装饰交互扩大架构改造。

覆盖服务端确认、保存期间撤销、失败、未收尾子项、跨路由/成员隔离、冷却与去重、输入/后台/安静/版本面板抑制、减少动态效果、有限 RAF、上下文丢失及资源释放、前景位置碰撞、真实空状态与筛选无结果区分。同步 main 后重跑受影响回归。

## 真实用户链路

使用既有 `5255 → 18080` 本地验收链路和正常登录，本机开发成员。未建立新数据库，未清库。专用内容精确标识：

- 项目 `p1790990344904`（已有验收专用项目）。
- 小记 `3474337e83534a1baa87e406d4e70f7a`，正文标识「验收专用-安静伙伴-20261003-1632」。
- 任务 `t5d0e1382d04e4d388fe2500ac9abf75`，编号 #30，标题「验收专用-安静伙伴完成回应-20261003-1636」。

重复执行时另建专用内容，并记录实际 ID；不得修改用户任务验证完成效果，不删除既有草稿。

1. 小记显式保存，等待真实成功后观察蓝色角色和自动退出；刷新后重新打开确认正文仍在。
2. 仅为该专用小记设置本地 PATCH 503 故障，观察失败提示、原文、原地重试按钮和角色数量为零；解除故障并重试，确认真实落库及蓝色回应。故障配置限定单个文档并设置自动过期，验收后清空。
3. 专用任务完成，确认保存前无角色、自动保存成功后单个粉色角色；撤销完成无回应，再完成可在冷却结束后回应。主任务完成但子项未收尾的反例由定向单元测试覆盖。
4. 打开工具→安静陪伴，检查开关与首页伙伴一致；刷新后同一成员同一标签页保留设置。恢复默认后再体验。
5. 输入聚焦、切页、滚动及 Escape 时角色退出；窄屏保留正文与操作区，找不到安全空间不强行展示。

本轮已通过上述专用小记真实保存、503 保留和恢复重试，以及 #30 完成后的粉色回应和撤销无回应；已通过工具与首页开关联动、刷新会话保持。390×844 首页仍有四个伙伴，页面宽度与视口同为 390，无横向溢出。未改动原有左侧小记草稿。临时故障已解除；具体截图和构建日志保留在本任务忽略目录 `.local/core-acceptance/quiet-*`。

真实空项目/知识空间未通过清除现有业务数据人为制造，加载、失败和空状态边界使用组件测试覆盖。减少动态效果及图形上下文丢失由渲染资源测试验证。发布后只检查版本、静态制品和健康，业务体验交用户验收。
