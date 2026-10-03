# 天人一卦本机执行与页面宽度

依据 ADR-308，补充既有每日卦象、首次自动解读及历史规格。

`GET /api/tianren/readings/ai-status` 仅登录成员读取，返回 `configured`、`message`、本人今日 `runStatus` / `runId`；未有运行时后两者为 null。状态读取不入队、不重新起卦，不开放节点和凭据。

手动与已授权自动解读复用本机 Codex，保留既有成员事务与私有会话。`secure_queued` 是本机等待，`provider_running` 是本机正在执行，不能将内部历史字段名当成另一个 API 供应商。`succeeded` 表示结果和会话回复已经同事务提交；`failed` 保留可重试提示。页面重新打开后仅在有待执行任务时每五秒查询，结束后读取持久化会话。关闭页面不等于取消模型调用。

页面和服务端不恢复 Runner、不要求配置集中 AI Run 账号。既有模型选择默认继续 GPT-6 Astra，本机动态目录用于校验其可见性。普通问答的既有获准 API 路径不受本次影响。

页面内容宽度使用公共变量，左右间距沿用 28px、窄屏 14px，主内容和关注点卡片在容器不足 780px 时变为单列。长解读换行，表格可横向滚动，输入及操作保持可达。

## 回归

```powershell
mvn -pl wao-server -am test '-Dtest=AiAssistantServiceTest,LocalNoteAiServiceTest,TianrenDailyAutomationServiceTest,TianrenReadingServiceTest,TianrenNotificationServiceTest,AiAssistantConversationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
cd wao-web
npm run test:unit -- --run src/__tests__/TianrenView.spec.ts --maxWorkers=2
```

本地真实应用沿用 `docs/runbooks/local-note-ai.md` 启动方式。在专用验收账号上确认群通知未启用，使用明确标记的合成关注点。验证起卦、Codex 解读、等待时刷新、重复操作拒绝、完成后刷新恢复，以及桌面 / 390px 宽度。登记卦象 / 会话 / 运行标识；无可恢复删除入口的卦象留在专用账号并如实记录，不能删除真实成员今日卦象。

CLI/MCP：这是现有浏览器私有算卦功能的执行出口与只读状态，不新增终端发起起卦或解读权限。现有工作区 MCP 没有算卦工具，本次不自动扩大旧令牌能力。以后若提供终端算卦须另行设计成员授权和幂等语义。
