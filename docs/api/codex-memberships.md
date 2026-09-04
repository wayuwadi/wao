# Codex 会员与重置卡契约

## 自动上报

Runner 心跳中的 Codex 额度报告新增可选字段：

```json
{"resetCredits":{"availableCount":2,"credits":[{"grantedAt":1788488900,"expiresAt":1791080900}]}}
```

示例仅说明结构。缺失或 `null` 表示未取得信息，不等于 0 张。明细仅保存发放与卡片到期时间（Unix 秒），最多 50 条；不传卡片 ID、描述或凭据。现有 `credits` 为金额额度，语义保持不变。额度汇总账号对象透传同名字段。

## 人工日期

- `GET /api/codex-memberships`：要求登录，返回 `ApiResponse<List<{account,membership}>>`；`account` 为按账号合并的现有 Codex 额度对象。
- `PUT /api/codex-memberships`：要求管理员，参数及成功响应的 `membership` 为 `{accountKey,purchasedAt,proExpiresAt,revision}`。日期格式 `YYYY-MM-DD`，空串或 `null` 清除日期。初次保存 revision 为空，之后携带最近读取的 revision。
- 未同步账号、非法日期与未来购买日期返回 400；修订不一致返回 409；不能用其他账号的 revision 覆盖记录。
- 人工日期位于独立 `system_setting` 记录中，不随 Runner 快照变化。到期提醒复用 `quota_refresh_notification_state`，键包含账号摘要和到期日；不改变已存在的额度基线格式。
