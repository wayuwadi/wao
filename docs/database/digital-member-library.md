# 数字成员图库字段与关系

迁移：`V189__digital_member_library.sql`；所有表的 `member_id` 表示服务器认证后的拥有者，32位标识不包含照片内容。私有图片不进入 `image` / `reference_image` 的旧共享或截图生命周期。

| 表 | 关键字段 | 约束与含义 |
| --- | --- | --- |
| digital_member_profile | member_id PK、gender、revision | 性别 unknown/male/female；拥有者事务锁、CAS；不自动推断 |
| digital_member_binding | member_id PK、subject_id、version_id、revision | 唯一本人及确认的默认版本；可仅绑定本人待建模；CAS |
| digital_subject | id PK、member_id、name、kind、relation、gender、traits、main_asset_id、revision、state、created_at | human/pet；active/archived；无他人账号自动绑定；资料/主参考 CAS |
| digital_asset | id PK、subject_id、member_id、name、role、content_type、original_content、content、content_hash、width、height、created_at | reference/portrait；MEDIUMBLOB原件与规范PNG；SHA256针对规范输入；所有读取鉴权 |
| digital_look | id PK、subject_id、member_id、name、style、revision、created_at | 一成员多个Look；追加版本 CAS |
| digital_appearance_version | id PK、look_id、member_id、version_number、state、asset_ids、portrait_asset_id、snapshot_json、created_at、confirmed_at | look_id+version_number 唯一；candidate/confirmed；资料不可变，确认只改状态 |
| digital_generation_use | run_id PK、member_id、subject_id、version_id、mode、purpose、input_hash、payload_hash、snapshot_json、result_look_id、result_version_id、created_at | 输入摘要用于请求幂等；快照包含事实、版本与资产清单/hash；生成结果回联不可变版本；状态读取ai_assistant_run |

ER：profile/binding 各归一个拥有者；subject 1:N asset、look、generation_use；look 1:N version；version 通过 asset_ids 和 portrait_asset_id 引用该subject的资产；generation_use 通过 run_id 关联已有执行队列。跨成员和跨subject关系由服务逐一验证，客户端不能提供 owner_id。修改与检查引用均在拥有者行锁事务内，避免删图与排队/版本创建竞态。

快照中保留 `subjectRevision`、gender/traits、versionId、appearance、assetIds、manifest(id/sha256)；不复制文章原文。服务端入队后不重新读取最新默认，不把终态已清除的提示词当成历史依据。后续外部平台绑定另设计独立表，不在本迁移中储存凭据、可移植性未知的模型权重或假能力。

`digital_note_identity`：doc_id/cycle_id/member_id 联合主键，selection_json、created_at。保存本轮人工选择，与既有 note_ai_save_signal/stage_attempt 的 cycle_id 相联；只保存身份指针，不保存照片字节或正文。自动阶段和重试不会读取另一轮选择。
