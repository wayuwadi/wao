ALTER TABLE kb ADD COLUMN owner_id VARCHAR(32) NULL;
CREATE INDEX idx_kb_owner ON kb(owner_id);

-- The operator must verify and set this member id before deploying on an existing installation.
-- No guessed author, default administrator, or public fallback for unassigned private libraries.
UPDATE kb k
JOIN system_setting s ON s.cfg_key = 'knowledge.legacy-owner-member-id'
JOIN member m ON m.id = s.cfg_value
SET k.owner_id = m.id
WHERE k.owner_id IS NULL AND COALESCE(k.notes, 0) = 0;
