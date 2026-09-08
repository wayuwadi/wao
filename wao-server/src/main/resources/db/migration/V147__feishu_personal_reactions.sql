CREATE TABLE feishu_reaction_grant (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    app_id VARCHAR(64) NOT NULL,
    open_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    access_cipher BLOB NOT NULL,
    access_nonce VARBINARY(12) NOT NULL,
    refresh_cipher BLOB NULL,
    refresh_nonce VARBINARY(12) NULL,
    expires_at DATETIME NOT NULL,
    refresh_expires_at DATETIME NULL,
    UNIQUE KEY uk_reaction_grant_identity (app_id, open_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE feishu_notification_feedback ADD COLUMN reaction_id VARCHAR(256) NULL;
ALTER TABLE feishu_notification_feedback ADD COLUMN reaction_action VARCHAR(16) NULL;
