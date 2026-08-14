DROP TABLE IF EXISTS tianren_daily_reading;
DROP TABLE IF EXISTS ai_assistant_message;
DROP TABLE IF EXISTS ai_assistant_conversation;

CREATE TABLE ai_assistant_conversation (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    last_message_preview VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE ai_assistant_message (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(32) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content CLOB NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_tianren_test_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_assistant_conversation(id) ON DELETE CASCADE
);
