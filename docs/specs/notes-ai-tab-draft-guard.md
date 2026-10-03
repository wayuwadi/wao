# 小记按需 AI 的页签草稿保护

补充 `notes-tab-draft-isolation.md` 与 `notes-single-latest-content.md`。

## 问题与决定

草稿已使用成员、文档和页签独立持久键，但浏览器按需 AI 的未保存判断仍读取旧共享键。隔离回归复现标题、配图、标签和润色四种迟到结果越过此判断；真实浏览器复现输入手写标题期间，AI 仍修改服务器标题及版本。编辑器自己的草稿保护仍有效，不能把该现象夸大为已经丢失编辑框内容。

- 按需 AI 的入队前及回写前检查统一调用现有 `readNoteDraft`，沿用本页签恢复与旧键迁移语义。
- 有未保存草稿时不写回标题、标签、配图或润色版本，保留任务并提示先保存后重试。
- 无法读取草稿时保守阻止自动回写，不把存储异常解释成没有编辑。
- 保存成功后的重试继续使用既有规则，不改变模型、服务端自动工作流、单份最新正文或其他页签的草稿归属。

这是浏览器草稿守卫修复，不新增 HTTP API、CLI、MCP 或数据库结构。

## 回归

```powershell
cd wao-web
npm run test:unit -- --run src/__tests__/autoNoteEnrichment.spec.ts src/__tests__/noteDraftStorage.spec.ts src/__tests__/NotesView.spec.ts --maxWorkers=2
```

真实验收沿用 `docs/runbooks/local-note-ai.md` 和专用合成小记：从卡片启动标题生成，确认已处理后打开编辑并输入手写标题；任务返回后检查标题、焦点和光标不变，且服务器版本没有自动推进。收起、刷新恢复草稿，保存后重试并从独立页面核对已保存结果。测试内容按精确标识可恢复删除。原始截图和运行标识只保留在本机证据目录。
