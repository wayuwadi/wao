CREATE TABLE drawing_public_share (
    id              CHAR(32)    NOT NULL,
    drawing_id      VARCHAR(32) NOT NULL,
    owner_member_id VARCHAR(32) NOT NULL,
    token_hash      CHAR(64)    NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    expires_at      DATETIME(6) NULL,
    revoked_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_drawing_share_token (token_hash),
    KEY idx_drawing_share_owner (drawing_id, owner_member_id, revoked_at, expires_at),
    CONSTRAINT fk_drawing_share_file FOREIGN KEY (drawing_id) REFERENCES drawing_file(id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
