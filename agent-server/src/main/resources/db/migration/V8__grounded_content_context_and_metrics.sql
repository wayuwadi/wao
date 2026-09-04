ALTER TABLE agent_publish_task
    ADD COLUMN source_notes LONGTEXT NULL AFTER source_hotspot_id,
    ADD COLUMN use_wao_context TINYINT(1) NOT NULL DEFAULT 1 AFTER source_notes,
    ADD COLUMN source_context_json LONGTEXT NULL AFTER use_wao_context,
    ADD COLUMN publication_url VARCHAR(1000) NULL AFTER platform_item_id;

CREATE TABLE agent_publish_metric_snapshot (
    id            VARCHAR(36) NOT NULL,
    publish_task_id VARCHAR(36) NOT NULL,
    workspace_id  VARCHAR(36) NOT NULL,
    views_count   BIGINT NULL,
    likes_count   BIGINT NULL,
    comments_count BIGINT NULL,
    favorites_count BIGINT NULL,
    shares_count  BIGINT NULL,
    recorded_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_metric_task_time (publish_task_id, recorded_at),
    KEY idx_agent_metric_workspace_time (workspace_id, recorded_at),
    CONSTRAINT fk_agent_metric_task FOREIGN KEY (publish_task_id)
        REFERENCES agent_publish_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_metric_workspace FOREIGN KEY (workspace_id)
        REFERENCES agent_workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
