DROP TABLE IF EXISTS personal_workspace;
DROP TABLE IF EXISTS account;

CREATE TABLE account (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE TABLE personal_workspace (
    id VARCHAR(32) PRIMARY KEY,
    owner_member_id VARCHAR(32) NOT NULL,
    project_id VARCHAR(32),
    name VARCHAR(60) NOT NULL,
    description_text VARCHAR(400) NOT NULL,
    content_json CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
