DROP TABLE IF EXISTS personal_memory;
DROP TABLE IF EXISTS account;

CREATE TABLE account (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE personal_memory (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    title VARCHAR(120) NOT NULL,
    content TEXT,
    category VARCHAR(32) NOT NULL,
    capture_mode VARCHAR(16) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    file_name VARCHAR(255),
    content_type VARCHAR(160),
    byte_size BIGINT,
    file_content BLOB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
