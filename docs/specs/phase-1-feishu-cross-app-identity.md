# Phase 1：飞书群聊收集跨应用身份

## 用户结果

- 同一成员使用 WAO 登录应用完成绑定后，可在独立群机器人应用中 `@`
  机器人收集自己的任务。
- 群聊 Open ID 与登录 Open ID 不同不再导致“未绑定”。
- 重名、无 Union ID 或 Union ID 归属冲突时必须拒绝，不得把任务记到默认成员。
- 已绑定的原 Open ID 继续可登录，下次登录自动补齐 Union ID。

## 数据与安全

- `account_identity.provider_union_subject` 与 `provider` 组合唯一，空值兼容旧数据。
- 身份查找先用应用内主体，再用 Union ID，两者指向不同账号时拒绝登录。
- 群成员 Open ID 与 Union ID 列表只在昵称一对一时配对，不使用相似名、顺序或默认用户。
- 既有 `feishu_quick_collect_event` 仍按消息 ID 去重；被拒绝的历史消息不自动重放。

## 验收

- 单元测试覆盖登录保存 Union ID、跨 Open ID 命中同一账号、Union ID 冲突、
  群成员唯一配对与重名拒绝。
- 生产受控回填后，用已绑定成员新发一条 `@`机器人消息，验证事件为
  `CREATED`、任务 owner 为发言者、无截止日期，且群内收到可打开任务的回执。
