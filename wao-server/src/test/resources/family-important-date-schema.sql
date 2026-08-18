CREATE TABLE account (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE family_important_date (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    event_date DATE NOT NULL,
    note VARCHAR(200),
    reminder_enabled BOOLEAN NOT NULL,
    reminder_days_before INT NOT NULL,
    reminder_time TIME,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
