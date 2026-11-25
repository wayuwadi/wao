CREATE TABLE agent_memory_revision (
    revision_id  VARCHAR(64)  NOT NULL,
    project_key  VARCHAR(120) NOT NULL DEFAULT '',
    source_agent VARCHAR(16)  NOT NULL,
    startup      BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (revision_id),
    CONSTRAINT fk_agent_memory_revision FOREIGN KEY (revision_id)
        REFERENCES memory_revision (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
