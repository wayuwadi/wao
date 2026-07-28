CREATE TABLE ai_account (
    id              VARCHAR(32) PRIMARY KEY,
    provider        VARCHAR(24)  NOT NULL COMMENT 'AI 工具：codex / claude / qoder',
    label           VARCHAR(128) NOT NULL COMMENT '展示名称',
    email           VARCHAR(255) NULL COMMENT '账号邮箱或标识',
    credential_json TEXT         NOT NULL COMMENT 'AES-256-GCM 加密后的凭据 JSON',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    quota_status    VARCHAR(24)  NULL COMMENT '额度同步状态：UNKNOWN / NO_API / SYNCED / FAILED',
    quota_message   VARCHAR(500) NULL COMMENT '额度/状态的人可读摘要',
    quota_raw_json  TEXT         NULL COMMENT '供应商返回的原始额度快照',
    last_synced_at  DATETIME     NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_provider_enabled (provider, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
