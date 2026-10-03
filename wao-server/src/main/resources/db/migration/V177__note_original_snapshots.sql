CREATE TABLE note_source_snapshot (
 id CHAR(32) PRIMARY KEY,
 doc_id VARCHAR(32) NULL,
 stable_id VARCHAR(32) NOT NULL,
 member_id VARCHAR(32) NOT NULL,
 source_key VARCHAR(100) NOT NULL,
 source_type VARCHAR(32) NOT NULL,
 source_rev INT NULL,
 user_generation BIGINT NULL,
 title TEXT NULL,
 markdown LONGTEXT NOT NULL,
 content_json LONGTEXT NOT NULL,
 content_hash CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_note_source_identity(member_id,stable_id,source_key),
 KEY idx_note_source_doc(doc_id,member_id,created_at),
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE,
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE note_source_asset_ref (
 snapshot_id CHAR(32) NOT NULL,
 asset_type VARCHAR(8) NOT NULL,
 asset_id VARCHAR(32) NOT NULL,
 PRIMARY KEY(snapshot_id,asset_type,asset_id),
 KEY idx_note_source_asset(asset_type,asset_id),
 FOREIGN KEY(snapshot_id) REFERENCES note_source_snapshot(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE note_original_record (
 doc_id VARCHAR(32) NOT NULL,
 member_id VARCHAR(32) NOT NULL,
 snapshot_id CHAR(32) NOT NULL,
 authenticity VARCHAR(32) NOT NULL,
 PRIMARY KEY(doc_id,member_id),
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE,
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE,
 FOREIGN KEY(snapshot_id) REFERENCES note_source_snapshot(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE note_ai_transformation (
 id CHAR(32) PRIMARY KEY,
 member_id VARCHAR(32) NOT NULL,
 stable_id VARCHAR(32) NOT NULL,
 doc_id VARCHAR(32) NULL,
 stage VARCHAR(16) NOT NULL,
 source_snapshot_id CHAR(32) NOT NULL,
 input_text LONGTEXT NOT NULL,
 input_hash CHAR(64) NOT NULL,
 input_rule VARCHAR(40) NOT NULL DEFAULT 'MODEL_INPUT_TRIMMED',
 selection_json TEXT NULL,
 output_json LONGTEXT NULL,
 task_id VARCHAR(36) NULL,
 application VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 applied_rev INT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_note_transformation_latest(doc_id,member_id,stage,application,updated_at),
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE,
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE,
 FOREIGN KEY(source_snapshot_id) REFERENCES note_source_snapshot(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
