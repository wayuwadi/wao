-- 独立反馈保留最小抑制依据，不能依赖 90 天后清理的非收藏产物。
ALTER TABLE note_discovery_feedback
 ADD topic_id CHAR(64) NULL,
 ADD conclusion_hash CHAR(64) NULL,
 ADD evidence_hash CHAR(64) NULL,
 ADD KEY idx_discovery_feedback_topic(member_id,topic_id,dismissed_at);
UPDATE note_discovery_feedback f JOIN note_mining_artifact a ON a.id=f.item_id AND a.member_id=f.member_id
 SET f.topic_id=a.topic_id,f.conclusion_hash=a.conclusion_hash,f.evidence_hash=a.evidence_hash WHERE a.origin='AUTO';
