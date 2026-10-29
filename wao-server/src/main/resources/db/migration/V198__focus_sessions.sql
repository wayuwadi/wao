CREATE TABLE focus_session (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(64) NOT NULL,
    active_member VARCHAR(64) NULL,
    mode VARCHAR(16) NOT NULL,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    ended_at BIGINT NULL,
    body_json LONGTEXT NOT NULL,
    UNIQUE KEY uk_focus_active (active_member),
    KEY idx_focus_history (member_id, ended_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
