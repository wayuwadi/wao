# 小记与本机 AI 加固收口记录

2026-10-03，[固定清单](notes-reliability-checklist-2026-10-03.md) A/B/C/D 已按下面的证据范围完成；确认缺陷已分别修复交付。用户已撤销原时间与额度条件，本记录不以额度消耗为完成标准。保持单份最新正文、本机现有 Codex 登录和配图，旧 Runner 下线。

## 当前发布与用户入口

- 13:44 只读核实公网前端 `338bea6c9f04fb71e82f51fade0c3f221dd0aa1f`、后端 `206a2686d2c558cbd0f414f958801e1049633aa5`。前端包含本表全部合并；后端包含本表全部服务端行为修改，后续为前端或资产变更。
- 最后一项历史恢复指引 PR #1102 已由后续项目页面发布批次覆盖，无需重复发布。小记、设置、天人一卦静态文件摘要和入口就绪通过，后端健康 UP、原进程保持运行，旧 Runner 端点 HTTP 410。
- 实际渠道为 Windows 临时服务与云端静态制品。Jenkins 尚未恢复（此前实测 401），本轮没有对应 Jenkins 构建链接；不宣称 Linux2 或旧生产数据库已经恢复。发布后没有执行线上业务写入验收。
- 用户刷新 WAO 后：在“小记”新建、编辑、收起恢复，触发标题/配图/润色并继续输入；选图按 Tab / Shift+Tab；在小记 AI 设置中选择账号目录实际可用模型；在“天人一卦”填写关注点并解读。线上业务效果由用户验收。

## 逐项交付

下表测试数是各提交当时的证据快照，不相加为覆盖率，不代表在最终版本重新执行了全部历史验收。完整方法与边界见对应证据；精确运行标识、合成正文及截图仅保留本机受控目录。

| PR / 合并 SHA | 改善与验证 | 交付状态 / 证据 |
| --- | --- | --- |
| [#1044](https://github.com/wayuwacn/wao/pull/1044) · `410ef1ed54ebfacc5b380a77aad0cab65929a757` | Windows 草稿验收工具修复，9 项通过 | 工程资产，无需部署；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1048](https://github.com/wayuwacn/wao/pull/1048) · `9e2c22d2be4404c66227ba840ee8c32eb6b14ca3` | 小记真实回归工具，7 类场景通过 | 工程资产，无需部署；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1052](https://github.com/wayuwacn/wao/pull/1052) · `9758e95ffff88fca30910f379ed645ba65e2d842` | 同源独立页签草稿隔离，79 项组件测试及 13 项工具测试、8 类真实场景通过 | 已发布，当前版本覆盖；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1057](https://github.com/wayuwacn/wao/pull/1057) · `ed7b6409580c123643a72782e81e82ed0c8fa930` | 当前服务端正文 AI 恢复资产，106 项服务端测试及 1 项报告校验测试通过 | 工程资产，无需部署；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1058](https://github.com/wayuwacn/wao/pull/1058) · `4ee52f557fc462840e01c74e09e7222788807da3` | 本机真实 Codex 正文链路、迟到输入和光标保护、失败重试、超时及隔离服务中断恢复；57 项测试通过 | 工程资产，无需部署；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1060](https://github.com/wayuwacn/wao/pull/1060) · `39d74a05612c9f8ce4677efaea1b442c386eae2b` | 图片 Tab 缩进与持久化；100 服务端、13 针对性前端，真实 Edge 保存刷新与窄屏通过 | 已发布，当前版本覆盖；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1062](https://github.com/wayuwacn/wao/pull/1062) · `f644422118c64d9377b81ade285ab79929a28e2b` | 本机配图和小记 AI 产品出口，真实超时/重启/光标验收通过；126 服务端+131 前端 | 已发布，当前版本覆盖；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1065](https://github.com/wayuwacn/wao/pull/1065) · `ada0772736e2f080665e5f66faba3633fe0f1874` | 标签请求内容改变后生成新请求号；65前端测试，真实Astra标签重试通过 | 已发布，当前版本覆盖；[验收](notes-ai-hardening-2026-10-02.md) |
| [#1067](https://github.com/wayuwacn/wao/pull/1067) · `ef5b67584e2d756529cc496a32dbe4756e921766` | 按需 AI 四阶段读取页签草稿；77测试和真实标题保护/保存重试通过 | 已发布，当前版本覆盖；[验收](notes-reliability-checklist-2026-10-03.md) |
| [#1068](https://github.com/wayuwacn/wao/pull/1068) · `d553390ab4b4090dd0b340180082f72062df35c3` | 动态本机Codex模型目录，真实6.1标题生成与偏好保存刷新通过 | 已发布，当前版本覆盖；[验收](notes-reliability-checklist-2026-10-03.md) |
| [#1073](https://github.com/wayuwacn/wao/pull/1073) · `84e4eaa6c8f2750dcc84730219016779b0c88db4` | 算卦统一本机 Codex 与公共宽度 | 已发布，当前版本覆盖；[验收](tianren-local-codex-2026-10-03.md) |
| [#1075](https://github.com/wayuwacn/wao/pull/1075) · `24876a0c36ed3fc111355f62c707c0f299417b6b` | CLI 稳定版自动验证升级及回退 | 已发布，当前版本覆盖；[验收](local-codex-update-2026-10-03.md) |
| [#1077](https://github.com/wayuwacn/wao/pull/1077) · `e9b80af5a19e7b820ddad9a79c9d98acd813091b` | 保护正文加载期间标题输入焦点 | 已发布，当前版本覆盖；[验收](notes-editor-focus-2026-10-03.md) |
| [#1079](https://github.com/wayuwacn/wao/pull/1079) · `5d9e623526cc684fbe0331680a8a861a293f2164` | 复制会话草稿身份隔离；88项、14工具及真实双页签通过 | 已发布，当前版本覆盖；[验收](notes-cloned-tabs-2026-10-03.md) |
| [#1082](https://github.com/wayuwacn/wao/pull/1082) · `503995bd9c769fc5bd6ff81821c30a248c66b990` | 阶段失败按实际阶段收口；134回归、事务恢复、真实完整自动链路及标签通过 | 已发布，当前版本覆盖；[验收](notes-workflow-stage-failure-2026-10-03.md) |
| [#1085](https://github.com/wayuwacn/wao/pull/1085) · `19d8e49386e34415c542861c92a8469836d7ab0e` | 手动完成状态保留；73回归与真实短小记润色、标题、刷新通过 | 已发布，当前版本覆盖；[验收](notes-manual-ai-progress-2026-10-03.md) |
| [#1086](https://github.com/wayuwacn/wao/pull/1086) · `588dddac499b6f3a600e30f2221028ddc599db19` | 保留未解决失败原因；138回归、持久事务及真实页面保存刷新重试通过 | 已发布，当前版本覆盖；[验收](notes-workflow-error-retention-2026-10-03.md) |
| [#1090](https://github.com/wayuwacn/wao/pull/1090) · `3216511ba7d065e5bddbe179c38ac7d0850463af` | 避免等待者阻挡完成回写；139回归、报告工具1项、真实两篇正常标题通过 | 已发布，当前版本覆盖；[验收](notes-workflow-queue-progress-2026-10-03.md) |
| [#1092](https://github.com/wayuwacn/wao/pull/1092) · `09cbcfa0f64eee06ca2b5b5f28e63707fec6f422` | 本地草稿拒写恢复工具；16工具及真实新建编辑拒写、恢复保存刷新通过 | 工程资产，无需部署；[验收](notes-storage-failure-2026-10-03.md) |
| [#1094](https://github.com/wayuwacn/wao/pull/1094) · `206a2686d2c558cbd0f414f958801e1049633aa5` | 串行成员上限修复，141项回归及真实Astra两篇依次处理通过；设置串行显示1并保留并行3；合成数据软删除 | 已发布，当前版本覆盖；[验收](notes-serial-member-limit-2026-10-03.md) |
| [#1096](https://github.com/wayuwacn/wao/pull/1096) · `ffcc4332fae8e2704473203d3c0a64ea2e1e6099` | 标签允许安全标题独立更新，90项回归；真实标签推荐期间保存标题，迟到结果完成且标题保留，刷新通过；合成内容软删除 | 已发布，当前版本覆盖；[验收](notes-tag-title-concurrency-2026-10-03.md) |
| [#1098](https://github.com/wayuwacn/wao/pull/1098) · `ed0670eb56b150c34da8fa8b6316aefd94545ce2` | 14套151项及报告工具1项通过；真实Astra标题和标签在删除后完成但文档保持删除、rev1与原内容哈希；无产品缺陷 | 工程资产，无需部署；[验收](notes-deleted-late-results-2026-10-03.md) |
| [#1102](https://github.com/wayuwacn/wao/pull/1102) · `647bcb5c50972f2939063d5eafffca0e988b2a3a` | 历史失败保留诊断并改用本机Codex恢复指引；57项、真实历史记录只读前后对照、390px几何通过 | 已发布，当前版本覆盖；[验收](notes-legacy-recovery-guidance-2026-10-03.md) |

## 可以重复执行的工具

从仓库根目录使用已有 Python、Java 17、Maven、Node 环境：

```powershell
python deploy/scripts/notes-ai-regression.py
python deploy/scripts/test_notes_ai_regression.py
npm --prefix wao-web run test:acceptance-tools
python deploy/scripts/check-engineering-consistency.py --base origin/main
```

- 服务端聚合最近一次为 **14 套 / 151 项通过**，报告工具 1 项通过；收口时核对原始报告和日志 SHA-256。聚合运行时 Git 基线 `ffcc4332`，工作区含随后交付的 PR #1098 测试，报告诚实记录 dirty；没有伪装成干净最终版本运行。
- 真实保存、失败恢复、多窗口：按[小记回归](../runbooks/notes-regression.md)启动本地应用、正常登录并执行浏览器步骤。命令测试不代替这些人工步骤。
- 完整本机 AI、真实 PNG、超时与恢复：按[本机产品运行](../runbooks/local-note-ai.md)使用现有登录。多篇隔离可使用 `-IsolateWorkflowState`，只隔离工作流表，不用于证明断线/重启持久性；重启证据来自此前普通专用验收实例。
- 图片缩进见[专项手册](../runbooks/editor-image-tab-regression.md)，草稿拒写故障入口只存在于本地验收模式，见[拒写验收](notes-storage-failure-2026-10-03.md)。
- CLI 稳定版更新、探测、失败保留及回退见[自动更新手册](../runbooks/local-codex-updates.md)。本机受管理版本验收为 0.160.0；计划任务 Ready，2026-10-03 09:59 试运行退出 0，下次计划 10-04 06:15。未来版本需要实际兼容探测通过才切换；模型目录自动刷新，不替换成员的已有模型选择。

## 明确的验证边界

- 多页签实测独立页签与 opener 复制会话；浏览器原生“复制标签页”菜单未直接操作。不能据此宣称所有浏览器复制实现已实测。
- 删除后真实标题、标签完成但旧结果不写回；归档保护由隔离测试覆盖。请求失效不等于上游模型已取消，小记没有独立取消端点。
- 本机专用实例真实超时、执行中断、重启恢复已验；传输断网与重复领取等故障部分由隔离测试覆盖。未为测试重启公网或中断其他任务，也没有多节点全局严格只执行一次的保证。
- 样本均为专用合成内容；本轮创建的小记已登记并可恢复软删除。天人一卦的合成私有会话因没有回收入口保留在专用账号，未删除已有卦象或其他用户内容。历史错误提示只读验收未修改原记录。
- 未来未知 CLI 版本、真实在途调用遇到升级切换、所有可见模型的配图能力没有逐一真实验证。自动升级失败保留原版本，不自动转用付费 API。
- 本机两次整体运行环境中断原因未确认，恢复使用既有数据库和制品。此项是环境运维风险，不作为本次故障注入成功证据或已修复结论。

## 质量评测决定与结束条件

本轮不启动额外质量评测、不修改提示词。完整合成样本实际保留数字、事实与否定，但一个样本不能证明普遍质量或比较收益；本轮没有复现需要调整提示词的质量缺陷。以后若用户提供具体失真案例，再建立版本化合成对照集，将自动事实规则与人工表达偏好分开。

固定范围的输入保护、实际 AI 路径、恢复状态、图片体验与工程资产已完成，发布终态明确。上述边界及环境运维风险保留，不扩大为新功能项目。原始证据索引在本机 `wao-evidence/notes-ai-20261003/completion-audit/audit.json`，接续入口仍为 `wao-evidence/notes-ai-20261002/handoff.json`。本收口文档属于工程资产，无需生产部署。
