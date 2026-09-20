CREATE TABLE wao_node_session_ticket (
    ticket_hash CHAR(64) NOT NULL PRIMARY KEY,
    root_session_id CHAR(36) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    target_origin VARCHAR(128) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    consumed_at_ms BIGINT NULL,
    INDEX idx_node_ticket_expiry (expires_at_ms),
    INDEX idx_node_ticket_root_created (root_session_id, created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
