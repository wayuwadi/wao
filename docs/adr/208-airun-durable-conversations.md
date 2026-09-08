# ADR-208：AiRun 持久会话、对话档案与按需验证

## 状态

已采纳（2026-09-08）。扩展 ADR-027、091、111、144、202。

## 问题与决策

- 旧 Runner 只在整轮完成后回传 sessionId；离线重排清掉原节点，且只有同时存在新输入才 resume。
  新实现在线程建立时写本机检查点并回传服务端，只接受所属节点的不可变会话绑定；异常重试与离线恢复
  均沿用精确 ID、原工作区和节点，不以新线程伪装恢复。原节点删除或工作区丢失时明确失败。
- Codex exec 与 goal app-server 均保存会话。运行详情展示可复制的会话 ID 和原节点，暂停后也可继续。
  ID 只在持有原 CODEX_HOME 会话文件的节点可恢复，不能用它直接把任意桌面会话搬到云端。
- Codex 新运行默认 GPT-6 Astra，保留 Sol / Terra / Luna，保持 high 推理、标准速度和自定义 API 覆盖规则。
  旧运行续聊保留已固化的模型，不重写历史。账号是否有权限仍由实际 CLI 返回决定，不静默降级模型。
- 对话保留换行和完整公开消息，扩展进度正文为 TEXT，摘要仍限 1000 字；已完成消息不参与覆盖式节流。
  用户输入单独标识，历史通过有界游标翻页补读。草稿按用户和运行隔离保存在本机，发送成功后清理，失败保留。
- 续聊优先只发送增量要求，不重复召回项目记忆。纯讨论不强制提交/发布；代码修改沿用相称验证和
  Jenkins 五分钟共享批次，不重复 Build、clean、全量测试或以模型高频轮询同一构建。
  纯讨论通过 `WAO_DELIVERY_STAGE: NOT_REQUIRED` 明确显示“无需部署”，不伪报部署成功；已进入等待部署的
  代码不能用该标记跳过验收。对话时间精确到微秒，首页 SQL 每个运行只取最近 50 条，历史按需加载。

## 参考与边界

参考 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui) 的
`ai-bridge/services/codex/message-service.js`（resumeThread、仅首轮注入 AGENTS）和模型服务设计。
该项目 MIT 许可。本次独立实现 Python/Java/Vue 适配，不复制其源代码或引入 JetBrains/React 运行时。
官方 [非交互模式](https://learn.chatgpt.com/docs/non-interactive-mode) 支持指定 SESSION_ID 续接；
[更新记录](https://learn.chatgpt.com/docs/changelog) 确认 GPT-6 Astra 模型目录。
本次提升可恢复性与交互质量，不宣称已与桌面 Codex 的全部审批、工具卡片和跨设备会话能力持平。

## 验证

覆盖建立会话后异常、回传临时失败、无新输入恢复、原节点离线、绑定冲突、暂停恢复、长消息/密集消息、
历史翻页、草稿失败保留、GPT-6 默认与旧模型兼容；按 ADR-202 跟进合并发布和真实页面验收。
