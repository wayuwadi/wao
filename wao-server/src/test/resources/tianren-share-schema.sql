DROP TABLE IF EXISTS tianren_daily_reading;
DROP TABLE IF EXISTS ai_assistant_conversation;

CREATE TABLE ai_assistant_conversation (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    last_message_preview VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);
