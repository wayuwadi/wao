-- 热点采集、工作区画像与选题建议。rank 在 MySQL 8 是保留字，故用 rank_no。

CREATE TABLE agent_hotspot (
    id            VARCHAR(36)  NOT NULL,
    source        VARCHAR(32)  NOT NULL,
    external_id   VARCHAR(128) NOT NULL,
    title         VARCHAR(500) NOT NULL,
    summary       VARCHAR(1000),
    url           VARCHAR(1000),
    heat          BIGINT,
    rank_no       INT          NOT NULL,
    first_seen_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_hotspot (source, external_id),
    KEY idx_agent_hotspot_seen (last_seen_at),
    KEY idx_agent_hotspot_source_rank (source, rank_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 内容定位。没有它，热点推荐只能给出泛泛的大众选题。
CREATE TABLE agent_workspace_profile (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(36)  NOT NULL,
    niche        VARCHAR(200),
    audience     VARCHAR(200),
    tone         VARCHAR(200),
    avoid        VARCHAR(500),
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_profile_workspace (workspace_id),
    CONSTRAINT fk_agent_profile_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_topic_suggestion (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(36)  NOT NULL,
    hotspot_id   VARCHAR(36),
    platform     VARCHAR(32)  NOT NULL,
    topic        VARCHAR(200) NOT NULL,
    angle        VARCHAR(1000),
    reason       VARCHAR(1000),
    fit_score    INT          NOT NULL DEFAULT 0,
    status       VARCHAR(24)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_suggestion_workspace (workspace_id, created_at),
    CONSTRAINT fk_agent_suggestion_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id),
    CONSTRAINT fk_agent_suggestion_hotspot FOREIGN KEY (hotspot_id) REFERENCES agent_hotspot (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 结构化产出：口播分段 / 文章小标题存 JSON，content 仍保留可直接发布的纯文本。
ALTER TABLE agent_publish_task ADD COLUMN structure_json LONGTEXT NULL;
ALTER TABLE agent_publish_task ADD COLUMN cover_text VARCHAR(200) NULL;
ALTER TABLE agent_publish_task ADD COLUMN source_hotspot_id VARCHAR(36) NULL;
