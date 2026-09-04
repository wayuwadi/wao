# Codex 重置卡与会员信息

对应任务 `t1788507652893`。用户已选择“自动同步＋手动补充”。

- Runner 从 Codex app-server `account/rateLimits/read` 的 `rateLimitResetCredits` 同步可用数量与发放/到期时间。`availableCount` 是权威汇总，明细可能缺失或截断，不用明细条数推算数量，不把原有金额 `credits.balance` 当重置卡。
- 官方 `grantedAt` 仅是发放时间，不标成购买时间。购买日期与 Pro 到期日由管理员手动补充，未填写为空，不估算、不伪造。
- 在首页“查看 AI 详情”进入的数据仪表盘展示每个真实账号的数量、发放记录、日期、来源新鲜度与编辑入口。多个执行电脑同账号合并展示。
- 新增可选 `resetCredits` 保持旧 Runner/历史 JSON 兼容。旧报告未提供时明确“尚未同步”；自动快照不覆盖人工日期。
- 人工日期按账号保存于 `system_setting`，键 `codex-membership-v1:` 加 SHA-256 的 Base64URL 摘要（无 padding，键小于 64 字符）。JSON 包含账号、两项日期和修订号。保存比较原始 JSON 并用新修订号更新，防止多人静默覆盖。
- API：已登录成员 GET `/api/codex-memberships`；管理员 PUT 同路径，参数 `accountKey/purchasedAt/proExpiresAt/revision`。未知账号拒绝保存；冲突 409 保留本机输入。
- 重置卡首次观测只建立基线，后续增加按账号通知 WAO 群，减少只更新基线。使用独立数量基线，不受额度百分比 100 上限影响，不被旧 Runner 空字段清空。
- Pro 到期前三天至到期当天每半小时检查；首次到达窗口提醒一次，投递失败下轮重试。按账号和到期日记录投递状态并加行锁，两个 API 节点复用同一记录。提醒遵循系统自动化通知/静默策略；本地 `wao.scheduling.enabled=false` 不发送群提醒。
- 表单覆盖加载、空账号、保存中、成功、失败重试和本机草稿恢复；刷新不会覆盖未保存编辑。群通知内容明确说明到期日来自人工填写。

官方字段依据：<https://developers.openai.com/codex/app-server/> 及本机 Codex CLI 导出的 `GetAccountRateLimitsResponse` schema；官方当前无购买时间、Pro 到期日字段。
