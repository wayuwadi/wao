CREATE TABLE note_visual_recap (
 id CHAR(32) PRIMARY KEY,
 member_id VARCHAR(32) NOT NULL,
 doc_id VARCHAR(32) NOT NULL,
 source_rev INT NOT NULL,
 source_text MEDIUMTEXT NOT NULL,
 run_id VARCHAR(32) NOT NULL,
 html MEDIUMTEXT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY idx_recap_owner(member_id,doc_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
