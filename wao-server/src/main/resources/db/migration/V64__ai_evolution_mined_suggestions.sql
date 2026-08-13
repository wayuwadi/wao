CREATE TABLE ai_evolution_suggestion (
    id           VARCHAR(32)  NOT NULL,
    batch_id     VARCHAR(32)  NOT NULL,
    ord          INT          NOT NULL,
    title        VARCHAR(128) NOT NULL,
    description VARCHAR(500) NOT NULL,
    icon         VARCHAR(64)  NOT NULL,
    goal         TEXT         NOT NULL,
    source_title VARCHAR(255) NOT NULL,
    source_url   VARCHAR(512) NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_evolution_suggestion_batch_ord (batch_id, ord),
    INDEX idx_ai_evolution_suggestion_batch_created (batch_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
