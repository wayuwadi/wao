-- 组织共享账号资源与验证码消息分开保存；密码、短信正文和验证码均由应用层 AES-256-GCM 加密。
CREATE TABLE account_resource (
    id                    VARCHAR(32)  NOT NULL,
    platform              VARCHAR(64)  NOT NULL,
    account_label         VARCHAR(128) NOT NULL,
    login_identifier      VARCHAR(256) NOT NULL DEFAULT '',
    phone_number          VARCHAR(32)  NOT NULL DEFAULT '',
    secret_ciphertext     VARBINARY(8192) NULL,
    secret_nonce          VARBINARY(32)   NULL,
    notes                 VARCHAR(1024) NOT NULL DEFAULT '',
    enabled               TINYINT(1)   NOT NULL DEFAULT 1,
    revision              INT          NOT NULL DEFAULT 1,
    created_by            VARCHAR(32)  NOT NULL,
    updated_by            VARCHAR(32)  NOT NULL,
    created_at            DATETIME(3)  NOT NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_account_resource_phone (phone_number),
    KEY idx_account_resource_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE verification_message (
    id                    VARCHAR(32)  NOT NULL,
    fingerprint           CHAR(64)     NOT NULL,
    phone_number          VARCHAR(32)  NOT NULL,
    sender                VARCHAR(128) NOT NULL DEFAULT '',
    source                VARCHAR(128) NOT NULL DEFAULT '',
    message_ciphertext    MEDIUMBLOB    NOT NULL,
    message_nonce         VARBINARY(32)   NOT NULL,
    code_ciphertext       VARBINARY(512)  NULL,
    code_nonce            VARBINARY(32)   NULL,
    received_at           DATETIME(3)  NOT NULL,
    forward_status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    forward_error         VARCHAR(256) NOT NULL DEFAULT '',
    created_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verification_message_fingerprint (fingerprint),
    KEY idx_verification_message_phone_time (phone_number, received_at),
    KEY idx_verification_message_created (created_at),
    CONSTRAINT chk_verification_forward_status
        CHECK (forward_status IN ('PENDING', 'SENT', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
