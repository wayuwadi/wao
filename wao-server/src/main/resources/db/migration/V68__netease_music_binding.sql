CREATE TABLE netease_music_binding (
    member_id VARCHAR(64) PRIMARY KEY,
    access_token_ciphertext BLOB NOT NULL,
    access_token_nonce VARBINARY(12) NOT NULL,
    refresh_token_ciphertext BLOB NOT NULL,
    refresh_token_nonce VARBINARY(12) NOT NULL,
    token_expires_at DATETIME NOT NULL,
    netease_user_id VARCHAR(128) NULL,
    nickname VARCHAR(128) NULL,
    avatar_url VARCHAR(1024) NULL,
    device_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'CONNECTED',
    last_error VARCHAR(512) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE netease_music_oauth_state (
    state VARCHAR(30) PRIMARY KEY,
    member_id VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    net_status VARCHAR(8) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_netease_oauth_member (member_id),
    KEY idx_netease_oauth_expiry (expires_at)
);
