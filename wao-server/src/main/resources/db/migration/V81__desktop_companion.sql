CREATE TABLE companion_pairing (
    id VARCHAR(32) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    poll_secret_hash CHAR(64) NOT NULL,
    client_device_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    account_id VARCHAR(36) NULL,
    member_id VARCHAR(32) NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    approved_at DATETIME(3) NULL,
    consumed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_companion_pairing_code (code_hash),
    KEY idx_companion_pairing_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE companion_device (
    id VARCHAR(32) NOT NULL,
    client_device_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    platform VARCHAR(32) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NULL,
    read_before_at DATETIME(3) NULL,
    revoked_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_companion_device_client (client_device_id),
    UNIQUE KEY uk_companion_device_token (token_hash),
    KEY idx_companion_device_account (account_id, created_at),
    KEY idx_companion_device_member (member_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE companion_activity_receipt (
    id VARCHAR(40) NOT NULL,
    device_id VARCHAR(32) NOT NULL,
    activity_id VARCHAR(64) NOT NULL,
    read_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_companion_receipt (device_id, activity_id),
    CONSTRAINT fk_companion_receipt_device FOREIGN KEY (device_id)
        REFERENCES companion_device (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
