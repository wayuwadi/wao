# 小记私人来源快照字段与关联

规格见 [小记不可变原稿与只读原文](../specs/notes-immutable-originals.md)，新增结构由 `V177__note_original_snapshots.sql` 建立。它们不替代当前 `doc` 正文，也不提供普通修订版本管理。

| 表 | 关键字段及约束 | 用途 |
|---|---|---|
| `note_source_snapshot` | `id`；`member_id + stable_id + source_key` 唯一；`doc_id` 可空；`source_rev`、`user_generation`、`source_type`、`created_at` | 不可变的完整来源；保存前用稳定草稿 ID，首次保存后绑定文档 |
| 同表内容 | `title`、`markdown`、`content_json`、`content_hash` | 原始标题和 Markdown 原样保存；JSON 包含富文本 document、blocks、links；摘要核对重试是否仍为同一输入 |
| `note_original_record` | `doc_id + member_id` 主键；`snapshot_id`、`authenticity` | 首份指针仅首次建立；`INITIAL` 有首次 AI 之前的依据，`FIRST_RETAINED` 不承诺最初原稿 |
| `note_ai_transformation` | `id` 为阶段 attempt / 手动请求 ID；`source_snapshot_id`、`stage`、`task_id` | 每轮来源、实际任务、结果的准确关联 |
| 同表输入输出 | `input_text`、`input_hash`、`input_rule`、`selection_json`、`output_json` | 实际模型输入与完整原稿分开；选段保存选择范围；输出保持完整候选结构 |
| 同表应用状态 | `application`、`applied_rev`、`created_at`、`updated_at` | `PENDING`、`DRAFT_RESULT`、`APPLIED`、`MIXED_APPLIED`；模型完成不等于已经采用 |
| `note_source_asset_ref` | `snapshot_id + asset_type + asset_id` 主键；资产查询索引 | 保留冻结来源中真实存在的图片与附件；与显式删除采用相同资产行锁 |

来源身份包括 `FIRST_SAVE`、阶段轮次与完整内容摘要、手动请求身份，以及旧对照更新时间及源/结果摘要。来源内容一经建立不得 upsert 覆盖；相同身份但不同内容返回冲突。正文 CAS、来源关联与 AI 写回在同一事务内完成。

成员与文档外键沿用永久删除级联；软删除立即不可读，恢复后重新校验权限。资产引用不扩大资产访问权限。私人未保存草稿没有自动过期。历史对照缺失的标题、结构和版本不以当前正文补造；更早已覆盖的原稿和已删除的资产无法恢复。

按篇读取最多返回最近采用的润色输入和首份记录；没有无界轮次查询。所有读操作同时验证真实会话成员、文档存续及当前访问权、快照私人归属。分享、搜索、CLI/MCP 普通文档读取不附带这些表的内容。
