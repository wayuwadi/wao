# 本机小记 AI 启用与回归

CLI 后续稳定版升级、指针配置、每日检查及回退见 [本机 Codex 自动更新](local-codex-updates.md)。原始可执行文件路径配置保持兼容。

依据 ADR-306。小记单份最新正文继续有效；本机调用沿用已登录 Codex 的 ChatGPT 额度，不需要新增 API Key。旧 Runner 保持下线。

## 运行产品服务

前提：Java 17、Maven、现有数据库环境变量与已登录的 Codex CLI，CLI 支持 `exec --ignore-user-config --ignore-rules` 和内置 `image_generation`。先用 `codex login status` 核对登录；不复制或打印认证文件。

在已有安全环境脚本加载数据库连接后，从仓库根目录运行：

```powershell
./deploy/scripts/start-local-note-ai.ps1 -CodexPath '<本机 codex.exe 绝对路径>' -Port 18082
```

入口使用 `local-cloud`、产品 main class、运行依赖，监听 127.0.0.1，关闭迁移与共享定时器。不会修改公网服务、创建 Runner 或添加开机任务。前端另开终端：

```powershell
$env:WAO_ACCEPTANCE_API='http://127.0.0.1:18082'
$env:WAO_ACCEPTANCE_PORT='5243'
cd wao-web
npm run dev:acceptance
```

默认状态目录为当前用户 `.wao/local-notes`。保留其中 `instance-id`，它决定队列归属；不同实例使用不同目录，不复制正在运行的状态目录。启用本机执行意味着允许登录 WAO 的成员按既有小记功能消费该主机 Codex 登录的额度；只在已有授权的主机运行。

真实验收时用 `-StateDirectory '<专用验收目录>' -DocumentIds '<合成小记ID,另一ID>'` 限制后台推进到登记的小记。未填写 DocumentIds 的日常模式推进已有待处理小记；不要对含其他用户待处理内容的共享库做无界故障验收。手动页面请求仍只由登录用户发起。`-TimeoutSeconds 1` 可在独立本机服务中复现超时，恢复默认 300 后重试；仅停止自己创建的测试进程，不重启公网或其他任务进程。

## 验收

自动检查不调用真实模型；可先从仓库根目录运行 `python deploy/scripts/notes-ai-regression.py` 生成带 SHA、测试计数和日志哈希的聚合报告。分项命令：

```powershell
mvn -pl wao-server -am test '-Dtest=LocalNoteAiServiceTest,LocalNoteCodexProcessTest,AiAssistantRunMapperIntegrationTest,AiAssistantServiceTest,ArticleImageServiceTest,ArticleImageControllerTest,NoteAiWorkflowServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
cd wao-web
npm run test:unit -- --run src/__tests__/MarkdownEditor.spec.ts src/__tests__/NotesView.spec.ts --maxWorkers=2
```

人工真实浏览器验收（不能由上面的 mock / SQL 测试替代）：

1. 专用合成正文至少 80 字，记录小记 ID；选择 Astra，检查标题、正文、配图依次完成。较短正文自动配图按原规则跳过，可用卡片配图按钮按需生成。
2. 配图完成前继续输入，检查草稿与光标；保存后刷新、重新打开，确认图片在既有图床且正文没有被旧结果覆盖。
3. 等待状态时刷新续接任务，不重复创建；已有首图再次生成时确认替换后才更换。
4. 在独立服务短超时条件下检查错误、原文与重试入口；恢复正常超时重试成功。中断本实例并重启，确认旧 claim 不能完成，旧 Runner 与其他实例任务不被领取。
5. 桌面和 390px 宽度检查按钮、设置、失败信息和图片；专用验收数据仅软删除并登记标识，保持可恢复。

保存无私有正文的报告：基线 / 分支 / PR / 合并 SHA、模型与 CLI 版本、合成文档 / 运行 / 图片 ID、操作结果和未验证项、截图路径及发布状态。运行日志和短期模型输出仅留本机私有目录，不进 Git。

## 恢复与边界

- 未启用 / 目录被另一服务占用：修正配置或停止重复实例后重启，不删除锁文件绕过正在运行的服务。
- CLI 失败：由服务运行账号检查 Codex 登录、网络、额度，然后点原小记失败阶段重试；不自动改用 API。
- 服务中断：本实例运行中任务变成可重试失败，保留正文；队列仍在数据库，超龄队列失败收口。崩溃时可能留下本机 job 临时文件或生成目录，按登记的任务归属人工检查，不能清理整个 Codex 目录。
- 公网是否更新由正常部署证据判断；本地通过与 PR 合并不能写成已上线。

阶段提交或回写异常的收口规则见 [阶段异常恢复](../specs/notes-workflow-stage-failure.md)。`python deploy/scripts/notes-ai-regression.py` 已包括 `NoteAiWorkflowTransactionTest`：只使用进程内 H2 验证先回滚再记录失败，以及重试不受旧错误覆盖。真实页面另按上面的步骤验收，不用隔离测试代替。

失败后其他阶段继续处理、或只编辑标题时，未解决的失败原因应保持可见；重试后才清除。检查与边界见 [失败原因保留验收](../validation/notes-workflow-error-retention-2026-10-03.md)。聚合命令同时验证保留、重试清除及部分失败解除后的提示，不需单独调用模型。

多篇等待而已有模型调用完成时，检查工作流是否正常释放入队名额。`notes-ai-regression.py` 的 `NoteAiWorkflowQueueTest` 覆盖 30 篇旧等待者不能挡住较新已入队阶段的完成回写，见 [队列推进验证](../validation/notes-workflow-queue-progress-2026-10-03.md)。工作流 RUNNING 包含模型排队与结果待回写，不代表 CLI 当前正在执行。

## 与日常执行器隔离的真实工作流验收

既有环境变量已加载后，可在专用本地实例运行：

```powershell
./deploy/scripts/start-local-note-ai.ps1 -CodexPath '<既有 Codex 或托管指针路径>' -Port 18084 -StateDirectory '<专用验收状态目录>' -IsolateWorkflowState
```

该可选参数将服务绑定到 127.0.0.1，要求专用端口和显式状态目录。单连接 Hikari 在每次建立连接时先创建临时表再改名为 `note_ai_workflow`，使日常实例无法领取验收工作流；初始化失败则连接失败，不回退到共享表。文档、登录、模型任务仍使用原数据库，页面和 Codex 均为真实产品链路。普通启动不启用这些参数。

MySQL 临时表只在当前连接可见，同名临时表遮蔽持久表，连接关闭后自动消失（[官方说明](https://dev.mysql.com/doc/refman/9.7/en/create-temporary-table.html)）。因此不能用此模式证明工作流的重启持久化；已有重启测试继续用登记 ID 的普通专用实例。验收中登记每条合成文档 ID，结束时通过回收站清理这些文档、恢复偏好，再停止自己的实例；不能清理整个共享库。

串行与并行成员上限的步骤及已知标签状态缺项见 [串行上限验证](../validation/notes-serial-member-limit-2026-10-03.md)。

删除与旧结果保护按 [迟到结果验证](../validation/notes-deleted-late-results-2026-10-03.md) 重跑：在专用实例中登记合成小记 ID，模型执行期间移入回收站，待调用结束后确认删除标记和内容哈希未变、刷新不存在。报告应分别记录模型调用终态与工作流结果是否采用，不把删除等同于取消调用。
