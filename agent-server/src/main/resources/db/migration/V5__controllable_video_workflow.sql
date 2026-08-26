-- 浏览器真实合成的视频工作流。与已退役的供应商生成任务分表，避免把本地合成冒充模型生成。
CREATE TABLE agent_video_project (
    id                    VARCHAR(36)  NOT NULL,
    workspace_id          VARCHAR(36)  NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    scenes_json           LONGTEXT     NOT NULL,
    status                VARCHAR(24)  NOT NULL,
    output_media_asset_id VARCHAR(36),
    version               INT          NOT NULL,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    exported_at           TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_video_project_workspace (workspace_id),
    CONSTRAINT fk_agent_video_project_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id),
    CONSTRAINT fk_agent_video_project_media FOREIGN KEY (output_media_asset_id) REFERENCES agent_media_asset (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
