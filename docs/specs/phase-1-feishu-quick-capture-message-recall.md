# Phase 1：飞书快速收集原消息撤回

本规格扩展 `phase-1-feishu-personal-quick-capture.md`、
`phase-1-feishu-personal-quick-capture-polling.md` 与
`phase-1-feishu-quick-capture-message-updates.md`。决策依据见
`docs/adr/183-feishu-quick-capture-message-recall.md`。

## 用户结果

- 成员在目标群 @ WAO 机器人创建任务后，撤回这条原消息会删除同一条任务；无需再进入 WAO 手工清理。
- 任务只按 `message_id → target_id` 的持久关系定位，不按标题、正文或创建时间猜测。
- 撤回事件先到、接收事件后到时不创建任务；重复撤回、Linux / Windows 双节点同时处理和任务已被手工删除
  都保持幂等。
- 管理员关闭快速收集开关后，已创建任务对应的撤回仍会处理，避免历史消息与任务失去一致性。

## 真实链路与安全边界

- 低延迟入口使用飞书官方 `im.message.recalled_v1`，回调继续校验既有 Verification Token / Encrypt Key，
  且只接受当前已配置目标群。
- 主动轮询把飞书消息的真实 `deleted=true` 作为回调兜底；不伪造撤回时间或根据“消息已读不到”推断删除。
- 无关联消息只留下以 `message_id` 为键的 `RECALLING` 墓碑，不删除任何 WAO 任务；同一消息 ID 但群 ID
  不一致时拒绝处理。
- 回调继续快速返回 200；暂时性失败由近 24 小时真实消息回看再次收敛。

## 验收

- 单元测试覆盖：回调撤回、非目标群拒绝、开关关闭后撤回、轮询撤回、重复/无关联撤回、撤回先于创建、
  创建与撤回并发时晚到任务立即删除。
- 设置页明确提示订阅 `im.message.recalled_v1`，并说明撤回会自动删除对应任务。
- 线上使用真实已绑定成员 @ 机器人创建任务后撤回原消息，确认任务列表和任务详情均不再返回该任务；
  重复刷新不重新出现。
