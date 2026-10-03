# 小记顺手发现持久化

依据 [ADR-314](../adr/314-notes-passive-discoveries.md)，加法结构见 [V174](../../wao-server/src/main/resources/db/migration/V174__notes_passive_discoveries.sql)。不得修改已执行迁移。所有时间使用数据库时间，调用预算日期按 UTC+8；指纹为 SHA-256。

| 表 | 关键字段与职责 | 约束与索引 |
| --- | --- | --- |
| note_discovery_schedule | member_id、enabled、enable_generation、change_seq/processed_seq、index_seq/processed_index_seq、next_run_at、active_job_id、current_artifact_id、last_published_at、lease_token/epoch/until、backfill_cursor/high_water、index_complete、version | member 主键；active_job_id 唯一；enabled/next_run_at/lease_until/member due 索引 |
| note_discovery_note_state | doc_id、member_id、doc_rev、body/user_body_fingerprint、user_basis_rev、change_origin、provenance、change_seq/processed_seq、stable_after、eligible、indexed_rev/index_version、index_payload_json、execution_scope | doc 主键；member/eligible/stable_after/change_seq；doc/member 删除级联 |
| note_discovery_index_term | member_id/doc_id、term_hash/text/type、weight、index_version | member/term_hash/doc 复合主键；doc/index_version 反查；按文档事务替换 |
| note_mining_job 扩展 | origin、enable_generation、input_seq/input_index_seq、input_fingerprint、pipeline/prompt_version、status/phase/result_type、execution_scope/model_snapshot、attempt_count/retry_at、started/finished/error、加密 input_cipher/nonce、candidate_json | member/generation/fingerprint/pipeline 唯一；origin/status/retry_at；旧 ai_run_id 允许空 |
| note_mining_artifact 扩展 | origin、kind、headline、next_step、topic_id、conclusion/evidence_hash、validity/invalid_reason、published/expires、supersedes | job/conclusion_hash 唯一；member/validity/published/id、member/topic |
| note_mining_artifact_source 扩展 | body_fingerprint、source_basis/basis_rev、claim_ids_json、quotes_json | 保留 artifact/doc 主键；doc/artifact 反查；一篇文档多个引用不构成独立证据 |
| note_discovery_topic_state | member/topic、object_key/conclusion_key、state、evidence_fingerprint、resolution_sources_json、last_artifact/user_change_seq、version | member/topic 主键；member/object/state 索引；最小解决/抑制依据长期保留 |
| note_discovery_feedback | member/item_id、item_kind、saved_at、dismissed_at、correction_text/corrected_at、legacy_status、version | member/item 主键；member/saved_at/item 游标索引；独立字段 CAS 更新 |
| note_discovery_ai_attempt | job/phase/attempt_no、ai_run_id、execution_scope、status/budget_day、provider/model/cli/prompt/input_hash、可空 tokens、duration/error | job/phase/attempt 唯一；ai_run_id 唯一；status/updated 索引 |
| note_discovery_daily_budget | day/scope/scope_id、reserved/started/retry_calls、batches/indexed_docs、usage_known_calls、可空 token 汇总/version | day/scope/scope_id 主键；按全局再成员固定顺序锁行 |
| note_polish_version 扩展 | verified_ai_job_id、source/output_fingerprint、source_rev、verified_at | 仅本人真实已完成润色任务能标记 verified；旧记录不自动认证 |

成员偏好键 note-discovery-v1 保存 enabled/version/generation；专用服务事务同步 schedule，通用偏好接口禁止写。旧产物和图片 origin=LEGACY；不创建新图片记录。反馈初始化只迁移目前可见的最后状态，无法恢复以前被覆盖的历史。收藏图片 ID 为 visual:job:lens，不复制图片或伪造发现。

冻结输入 AES-256-GCM 使用既有服务端密钥。成功 24 小时、其他终态最多 7 天清除输入、候选和关联调用文本；新未收藏产物 90 天清除，无产物关联的终态批次/attempt 随后清理；保留收藏所需元数据。预算 180 天。源删除/归档/权限撤销立即从检索移除且读取隐藏全部私人内容。清理与功能是否暂停独立；本地验收显式关闭清理并限定成员/文档。旧产物永久保留。

租约过期条件领取递增 epoch。冻结批次只确认本批材料的序号，并用剩余最小未处理序号约束成员游标；不能清掉未选材料或运行中新保存。代次、来源版本、正文指纹、有效租约四道发布检查不由模型决定。

[V175](../../wao-server/src/main/resources/db/migration/V175__discovery_feedback_suppression_keys.sql) 在独立反馈增加 topic_id/conclusion_hash/evidence_hash 与 member/topic/dismissed 索引。非收藏产物过期删除后，仍可抑制被否定的同结论；不改写已经应用的 V174。已解决主题和最小否定依据会补入审查上下文，不能因旧产物清理忘记历史反馈。
