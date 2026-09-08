CREATE TABLE ai_session_preference (
    member_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    title VARCHAR(160) NULL,
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (member_id, run_id)
);
