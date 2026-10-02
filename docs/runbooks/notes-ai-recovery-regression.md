# 小记正文 AI 执行与恢复回归

当前链路：`NoteComposer` 保存选择 → `NoteAiWorkflowService` → `TextPolishJobService` 的 `NOTE_BODY` → `AiAssistantService` 的 `provider_queued/provider_running` → 获准的服务端 API → 来源摘要及文档版本条件回写。

ADR-303/304 已下线项目 AI Run 和 Runner。小记正文保留服务端 API 润色；不再检查或恢复旧 Runner，不能将历史助手协议测试通过当作当前链路可用。

## 一条命令

在仓库根目录，使用已有 Python 3、Java 17、Maven 和 Git：

```bash
python deploy/scripts/notes-ai-regression.py
```

Maven 不在 PATH 时使用 `--maven /已有安装/bin/mvn`（Windows 指向 `mvn.cmd`）。工具不读取私有部署环境文件、不启动执行器、不调用外部模型。服务端沿用 H2 测试配置，关闭外部调度；上游响应及断网使用 Spring 测试 HTTP 传输替身。真实 SQL CAS 在 H2 内存库验证，不代表真实 MySQL 并发或模型执行验收。

报告位于 Git 忽略的 `wao-server/target/notes-ai-regression/<runId>/report.json`，包含 Git SHA、工作区状态、时间、各套测试计数、日志哈希，以及真实 AI/部署未验证标记。缺少本轮报告、跳过、空测试集或命令失败均不能通过。每轮使用独立目录；长期证据复制到本机受控目录，日志不提交 Git。

分项命令：

```bash
mvn -pl wao-server -am -Dtest=NoteAiWorkflowServiceTest,TextPolishJobServiceTest,AiAssistantServiceTest,AiAssistantRunMapperIntegrationTest,DeepSeekApiClientTest,FlywayMigrationsTest -Dsurefire.failIfNoSpecifiedTests=false test
python deploy/scripts/test_notes_ai_regression.py
```

前端迟到结果和草稿保护仍按[小记浏览器回归](notes-regression.md)及对应组件测试执行。

## 故障、证据和边界

| 情况 | 隔离判据与当前恢复方式 | 证据位置 |
| --- | --- | --- |
| 当前正文路由 | 使用获准的 POLISH Provider；不写入旧 Runner 队列，不扩大后台用途权限 | AiAssistantServiceTest.noteBodyUsesAuthorizedProviderWithoutRetiredRunnerQueue |
| 重复领取 | SQL CAS 只允许一个 worker 领取；失败领取不调用模型或创建另一任务 | AiAssistantRunMapperIntegrationTest、AiAssistantServiceTest |
| 成功与重复结果 | 成功仅接受当前令牌；重复成功、迟到失败和预览不能改变已成功正文 | Mapper 集成测试 |
| 流式断网或截断 | 半篇仅用于预览；断网返回可重试原因，不能当最终正文，也不自动发起另一次付费流式调用 | DeepSeekApiClientTest、Provider worker 测试 |
| 失败保留输入 | 运行终态清除临时提示及部分输出，润色任务仍保留原文；失败查询不把部分输出返回为成功结果 | Mapper、TextPolishJobServiceTest |
| 进程重启 | 持久等待任务由新 worker 领取；已执行但未终态的任务按既有执行超时进入失败，再由用户重试。没有承诺接续同一次上游流或模型严格只执行一次 | 领取 CAS、超时清理及工作流重试测试；真实重启待验 |
| 排队/执行超时 | 按数据库时间与领取时间判断；清除旧令牌，旧 worker 的终态 CAS 不成功，不发布历史 | 现有超时调度测试、新增 worker CAS 测试 |
| 取消与输入更新 | 小记没有独立取消端点。保存新正文使旧任务 ID 失效，不等于停止上游执行；未保存输入由浏览器草稿保护。内部终止后不再接受迟到结果 | NoteAiWorkflowServiceTest、NotesView.spec.ts |
| 重试 | 只重置失败阶段；正文变化绑定最新来源；完成的标题和图片不重复提交 | NoteAiWorkflowServiceTest |

聚合服务测试中仍含历史 Runner 用例；它们只维护遗留实现兼容性，不证明已退役端点可用。当前链路的证据以上表为准。

## 真实环境恢复后的待验

1. 当前公网临时数据库及服务没有恢复可用真实模型配置。本轮不切换到额外付费 API，不创建供应商账号或扩大用途权限。取得明确可用且获授权的执行配置后，才进行真实模型验收。
2. 在本地服务连接既有数据库，用独立标识的合成小记仅选择正文润色，登记精确文档、工作流、任务和运行标识。
3. 检查真实队列、执行和正文回写，继续输入时观察光标与未提交内容；保存新正文后确认旧任务结果不覆盖它。只在隔离本地 worker 制造断网或重启，不操作其他用户的任务。
4. 核对失败原因和原文恢复，再精确软删除本轮验收小记。原始正文、业务标识和截图留本机受控目录。
5. 发布独立核对 Jenkins 构建、检出 SHA 的覆盖关系、版本和健康。本工具通过不能替代真实模型、MySQL 或发布验收。

本项只新增测试和证据工具，不改变业务服务、HTTP、MCP 或 CLI 能力，不扩大现有令牌权限，不需要部署运行制品。
