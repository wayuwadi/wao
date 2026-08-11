-- 多平台账号接管与发布链路。抖音是首个可真实发布的适配器，其余平台先由代码声明能力。

CREATE TABLE agent_platform_account (
    id                       VARCHAR(36)  NOT NULL,
    workspace_id             VARCHAR(36)  NOT NULL,
    platform                 VARCHAR(32)  NOT NULL,
    platform_user_id         VARCHAR(128) NOT NULL,
    display_name             VARCHAR(128) NOT NULL,
    avatar_url               VARCHAR(512),
    status                   VARCHAR(24)  NOT NULL,
    scope                    VARCHAR(512),
    credential_cipher        LONGTEXT     NOT NULL,
    access_token_expires_at  TIMESTAMP    NULL,
    refresh_token_expires_at TIMESTAMP    NULL,
    last_error               VARCHAR(1000),
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_platform_account (workspace_id, platform, platform_user_id),
    KEY idx_agent_platform_account_ws (workspace_id, platform),
    CONSTRAINT fk_agent_platform_account_ws FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 素材落在处理节点的本地磁盘，因此记录 node_id，发布任务只能由同一节点执行。
CREATE TABLE agent_media_asset (
    id            VARCHAR(36)  NOT NULL,
    workspace_id  VARCHAR(36)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type  VARCHAR(128) NOT NULL,
    byte_size     BIGINT       NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    node_id       VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_media_workspace (workspace_id, created_at),
    CONSTRAINT fk_agent_media_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_publish_task (
    id                      VARCHAR(36)  NOT NULL,
    workspace_id            VARCHAR(36)  NOT NULL,
    requested_by_account_id VARCHAR(36)  NOT NULL,
    platform                VARCHAR(32)  NOT NULL,
    platform_account_id     VARCHAR(36),
    media_asset_id          VARCHAR(36),
    topic                   VARCHAR(200) NOT NULL,
    title                   VARCHAR(200),
    content                 LONGTEXT,
    tags_json               VARCHAR(1000),
    status                  VARCHAR(32)  NOT NULL,
    progress                INT          NOT NULL DEFAULT 0,
    node_id                 VARCHAR(64),
    platform_item_id        VARCHAR(128),
    error_message           VARCHAR(1000),
    version                 INT          NOT NULL DEFAULT 1,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    published_at            TIMESTAMP    NULL,
    PRIMARY KEY (id),
    KEY idx_agent_publish_workspace (workspace_id, created_at),
    KEY idx_agent_publish_status (status, updated_at),
    CONSTRAINT fk_agent_publish_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id),
    CONSTRAINT fk_agent_publish_requester FOREIGN KEY (requested_by_account_id) REFERENCES account (id),
    CONSTRAINT fk_agent_publish_platform_account FOREIGN KEY (platform_account_id) REFERENCES agent_platform_account (id),
    CONSTRAINT fk_agent_publish_media FOREIGN KEY (media_asset_id) REFERENCES agent_media_asset (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
