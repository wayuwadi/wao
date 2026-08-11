-- Agent 与 WAO 共用 account/member/SPRING_SESSION；仅维护 agent_ 前缀业务表。

CREATE TABLE agent_workspace (
    id               VARCHAR(36)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    owner_account_id VARCHAR(36)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_workspace_owner (owner_account_id),
    CONSTRAINT fk_agent_workspace_owner FOREIGN KEY (owner_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_workspace_member (
    id           VARCHAR(36) NOT NULL,
    workspace_id VARCHAR(36) NOT NULL,
    account_id   VARCHAR(36) NOT NULL,
    role         VARCHAR(24) NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_workspace_member (workspace_id, account_id),
    CONSTRAINT fk_agent_member_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id),
    CONSTRAINT fk_agent_member_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_content_draft (
    id           VARCHAR(36)  NOT NULL,
    workspace_id VARCHAR(36)  NOT NULL,
    title        VARCHAR(200) NOT NULL,
    channel      VARCHAR(32)  NOT NULL,
    content      LONGTEXT     NOT NULL,
    status       VARCHAR(24)  NOT NULL,
    version      INT          NOT NULL DEFAULT 1,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_draft_workspace (workspace_id, updated_at),
    CONSTRAINT fk_agent_draft_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE agent_run (
    id                      VARCHAR(36)  NOT NULL,
    workspace_id            VARCHAR(36)  NOT NULL,
    requested_by_account_id VARCHAR(36)  NOT NULL,
    topic                   VARCHAR(200) NOT NULL,
    channel                 VARCHAR(32)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    progress                INT          NOT NULL DEFAULT 0,
    result_summary          LONGTEXT,
    error_message           VARCHAR(1000),
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_agent_run_workspace (workspace_id, created_at),
    KEY idx_agent_run_status (status, updated_at),
    CONSTRAINT fk_agent_run_workspace FOREIGN KEY (workspace_id) REFERENCES agent_workspace (id),
    CONSTRAINT fk_agent_run_account FOREIGN KEY (requested_by_account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
