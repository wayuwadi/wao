ALTER TABLE note_polish_version
 ADD verified_ai_job_id VARCHAR(36) NULL,
 ADD source_body_fingerprint CHAR(64) NULL,
 ADD verified_output_fingerprint CHAR(64) NULL,
 ADD source_rev INT NULL,
 ADD verified_at DATETIME(6) NULL;

ALTER TABLE note_mining_job
 MODIFY ai_run_id VARCHAR(32) NULL,
 ADD origin VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
 ADD enable_generation BIGINT NULL,
 ADD input_seq BIGINT NULL,
 ADD input_index_seq BIGINT NULL,
 ADD input_fingerprint CHAR(64) NULL,
 ADD pipeline_version VARCHAR(32) NULL,
 ADD prompt_version VARCHAR(32) NULL,
 ADD status VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
 ADD phase VARCHAR(24) NULL,
 ADD result_type VARCHAR(32) NULL,
 ADD execution_scope VARCHAR(64) NULL,
 ADD model_snapshot VARCHAR(128) NULL,
 ADD attempt_count INT NOT NULL DEFAULT 0,
 ADD retry_at DATETIME(6) NULL,
 ADD started_at DATETIME(6) NULL,
 ADD finished_at DATETIME(6) NULL,
 ADD error_code VARCHAR(32) NULL,
 ADD input_cipher MEDIUMBLOB NULL,
 ADD input_nonce VARBINARY(12) NULL,
 ADD candidate_json JSON NULL,
 ADD UNIQUE KEY uk_discovery_batch(member_id,enable_generation,input_fingerprint,pipeline_version),
 ADD KEY idx_discovery_job_due(origin,status,retry_at);

ALTER TABLE note_mining_artifact
 ADD origin VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
 ADD kind VARCHAR(24) NULL,
 ADD headline VARCHAR(100) NULL,
 ADD next_step VARCHAR(300) NULL,
 ADD topic_id CHAR(64) NULL,
 ADD conclusion_hash CHAR(64) NULL,
 ADD evidence_hash CHAR(64) NULL,
 ADD validity VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
 ADD invalid_reason VARCHAR(32) NULL,
 ADD published_at DATETIME(6) NULL,
 ADD expires_at DATETIME(6) NULL,
 ADD supersedes_id VARCHAR(36) NULL,
 ADD UNIQUE KEY uk_discovery_artifact(job_id,conclusion_hash),
 ADD KEY idx_discovery_visible(member_id,validity,published_at,id),
 ADD KEY idx_discovery_topic(member_id,topic_id);

ALTER TABLE note_mining_artifact_source
 ADD body_fingerprint CHAR(64) NULL,
 ADD source_basis VARCHAR(24) NULL,
 ADD basis_rev INT NULL,
 ADD claim_ids_json JSON NULL,
 ADD quotes_json JSON NULL,
 ADD KEY idx_discovery_source_doc(doc_id,artifact_id);

CREATE TABLE note_discovery_schedule (
 member_id VARCHAR(32) NOT NULL PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 enable_generation BIGINT NOT NULL DEFAULT 0,
 change_seq BIGINT NOT NULL DEFAULT 0,
 processed_seq BIGINT NOT NULL DEFAULT 0,
 index_seq BIGINT NOT NULL DEFAULT 0,
 processed_index_seq BIGINT NOT NULL DEFAULT 0,
 next_run_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 active_job_id VARCHAR(36) NULL,
 current_artifact_id VARCHAR(36) NULL,
 last_published_at DATETIME(6) NULL,
 lease_token CHAR(32) NULL,
 lease_epoch BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(6) NULL,
 backfill_cursor_json JSON NULL,
 backfill_high_water_json JSON NULL,
 index_complete BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_discovery_active_job(active_job_id),
 KEY idx_discovery_schedule(enabled,next_run_at,lease_until,member_id),
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE note_discovery_note_state (
 doc_id VARCHAR(32) NOT NULL PRIMARY KEY,
 member_id VARCHAR(32) NOT NULL,
 doc_rev INT NOT NULL,
 body_fingerprint CHAR(64) NOT NULL,
 user_body_fingerprint CHAR(64) NULL,
 user_basis_rev INT NULL,
 change_origin VARCHAR(24) NOT NULL,
 provenance VARCHAR(24) NOT NULL,
 change_seq BIGINT NOT NULL DEFAULT 0,
 processed_seq BIGINT NOT NULL DEFAULT 0,
 dirty_since DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 stable_after DATETIME(6) NOT NULL,
 eligible BOOLEAN NOT NULL,
 indexed_rev INT NULL,
 index_version VARCHAR(32) NULL,
 index_payload_json JSON NULL,
 execution_scope VARCHAR(64) NULL,
 KEY idx_discovery_dirty(member_id,eligible,stable_after,change_seq),
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE,
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE note_discovery_index_term (
 member_id VARCHAR(32) NOT NULL,
 doc_id VARCHAR(32) NOT NULL,
 term_hash CHAR(64) NOT NULL,
 term_text VARCHAR(128) NOT NULL,
 term_type VARCHAR(24) NOT NULL,
 weight INT NOT NULL,
 index_version VARCHAR(32) NOT NULL,
 PRIMARY KEY(member_id,term_hash,doc_id),
 KEY idx_discovery_index_doc(doc_id,index_version),
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE note_discovery_topic_state (
 member_id VARCHAR(32) NOT NULL,
 topic_id CHAR(64) NOT NULL,
 object_key VARCHAR(200) NOT NULL,
 conclusion_key CHAR(64) NOT NULL,
 state VARCHAR(16) NOT NULL,
 evidence_fingerprint CHAR(64) NOT NULL,
 resolution_sources_json JSON NULL,
 last_artifact_id VARCHAR(36) NULL,
 last_user_change_seq BIGINT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(member_id,topic_id),
 KEY idx_discovery_topic_state(member_id,object_key,state),
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE note_discovery_feedback (
 member_id VARCHAR(32) NOT NULL,
 item_id VARCHAR(96) NOT NULL,
 item_kind VARCHAR(24) NOT NULL,
 saved_at DATETIME(6) NULL,
 dismissed_at DATETIME(6) NULL,
 correction_text VARCHAR(500) NULL,
 corrected_at DATETIME(6) NULL,
 legacy_status VARCHAR(24) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(member_id,item_id),
 KEY idx_discovery_saved(member_id,saved_at,item_id),
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE note_discovery_ai_attempt (
 id VARCHAR(32) NOT NULL PRIMARY KEY,
 job_id VARCHAR(36) NOT NULL,
 phase VARCHAR(24) NOT NULL,
 attempt_no INT NOT NULL,
 ai_run_id VARCHAR(32) NULL,
 execution_scope VARCHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,
 budget_day DATE NULL,
 provider VARCHAR(32) NOT NULL DEFAULT 'local_codex',
 model VARCHAR(128) NOT NULL,
 cli_version VARCHAR(64) NULL,
 prompt_version VARCHAR(32) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 input_tokens BIGINT NULL,
 cached_input_tokens BIGINT NULL,
 output_tokens BIGINT NULL,
 duration_ms BIGINT NULL,
 error_code VARCHAR(32) NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_discovery_attempt(job_id,phase,attempt_no),
 UNIQUE KEY uk_discovery_attempt_run(ai_run_id),
 KEY idx_discovery_attempt_status(status,updated_at),
 FOREIGN KEY(job_id) REFERENCES note_mining_job(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE note_discovery_daily_budget (
 budget_day DATE NOT NULL,
 scope VARCHAR(16) NOT NULL,
 scope_id VARCHAR(64) NOT NULL,
 reserved_calls INT NOT NULL DEFAULT 0,
 started_calls INT NOT NULL DEFAULT 0,
 retry_calls INT NOT NULL DEFAULT 0,
 batches INT NOT NULL DEFAULT 0,
 indexed_docs INT NOT NULL DEFAULT 0,
 usage_known_calls INT NOT NULL DEFAULT 0,
 input_tokens BIGINT NULL,
 output_tokens BIGINT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(budget_day,scope,scope_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO note_discovery_feedback(member_id,item_id,item_kind,saved_at,dismissed_at,legacy_status)
 SELECT member_id,id,'ARTIFACT',IF(feedback_status='SAVED',updated_at,NULL),
 IF(feedback_status='NOT_ACCURATE',updated_at,NULL),feedback_status FROM note_mining_artifact;
INSERT IGNORE INTO note_discovery_feedback(member_id,item_id,item_kind,saved_at,dismissed_at,legacy_status)
 SELECT member_id,CONCAT('visual:',job_id,':',lens),'LEGACY_VISUAL',
 IF(feedback_status='SAVED',updated_at,NULL),IF(feedback_status='NOT_ACCURATE',updated_at,NULL),feedback_status FROM note_mining_visual;
