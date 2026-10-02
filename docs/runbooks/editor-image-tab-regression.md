# 图片 Tab 缩进回归

## 自动回归

```powershell
npm --prefix wao-web run test:unit -- --run src/__tests__/MarkdownEditor.spec.ts src/__tests__/markdown.spec.ts -t '图片 Tab 缩进|表格 Tab|应用尺寸后|renderMarkdown'
mvn -pl wao-server -am test '-Dtest=KnowledgeServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
npm --prefix wao-web run build
```

覆盖节点仍存在、URL/尺寸/标题/正文不变、单次撤销、连续增减与 0–8 边界、无尺寸 Markdown 往返、表格导航、服务端持久化和非法缩进拒绝。隔离单测不代替真实浏览器验收。

## 真实浏览器步骤（人工或 Codex 浏览器工具）

1. 沿用既有数据库连接启动独立 local-cloud 后端与前端，不运行迁移或共享调度器。
2. 用正常账号新建标题以 `WAO-IMAGE-TAB-ACCEPTANCE-` 开头的小记，正文含前文、合成图片、后文。登记小记 ID 和本次上传的图片 ID，不使用真实私有正文作测试素材。
3. 双击卡片进入编辑，点击图片，确认高亮的是图片；Tab 两次、Shift+Tab 一次，依次为 48px、24px。撤销和重做后仍只有一张图片，前后正文不变。
4. 保存修改，等待成功后刷新页面，再次编辑。确认同一图仍在、缩进 24px。Shift+Tab 恢复原位，保存再打开复核。
5. 在 390px 窄屏测试图片缩进，确认图片可见、没有横向溢出；截图需等真实画面渲染后检查，不以 DOM 数值冒充截图。
6. 专用小记通过产品回收站软删除；记录图片 ID，按既有验收资源清理规则处理，不批量删图或清空回收站。

若浏览器工具的快捷键产生了意外正文，停止该页操作，先核对输入工具再继续，不能把工具异常产生的内容作为功能通过证据。
