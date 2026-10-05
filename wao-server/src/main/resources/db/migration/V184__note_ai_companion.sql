CREATE TABLE note_ai_companion_preference (
 member_id VARCHAR(32) PRIMARY KEY,
 result_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
 version BIGINT NOT NULL DEFAULT 0,
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE note_ai_stage_attempt ADD COLUMN result_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO';
ALTER TABLE note_ai_stage_attempt ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
CREATE TABLE note_ai_artifact (
 id CHAR(32) PRIMARY KEY,
 member_id VARCHAR(32) NOT NULL,
 doc_id VARCHAR(32) NOT NULL,
 stage VARCHAR(16) NOT NULL,
 source_rev INT NOT NULL,
 source_json LONGTEXT NOT NULL,
 result_text LONGTEXT NOT NULL,
 dependency_hash CHAR(64) NOT NULL,
 application VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 applied_rev INT NULL,
 applied_json LONGTEXT NULL,
 seen_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_companion_artifact(member_id,doc_id,created_at,id),
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE,
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE note_ai_conversation (
 member_id VARCHAR(32) NOT NULL,
 doc_id VARCHAR(32) NOT NULL,
 conversation_id VARCHAR(32) NOT NULL,
 sources_json TEXT NOT NULL,
 PRIMARY KEY(member_id,doc_id),
 FOREIGN KEY(doc_id) REFERENCES doc(id) ON DELETE CASCADE,
 FOREIGN KEY(member_id) REFERENCES member(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE note_ai_chat_request (
 id CHAR(32) PRIMARY KEY,
 member_id VARCHAR(32) NOT NULL,
 doc_id VARCHAR(32) NOT NULL,
 input_hash CHAR(64) NOT NULL,
 run_id VARCHAR(32) NOT NULL,
 sources_json TEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_companion_chat(member_id,doc_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
