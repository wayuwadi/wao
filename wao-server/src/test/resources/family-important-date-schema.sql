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
    calendar_type VARCHAR(16) NOT NULL DEFAULT 'SOLAR',
    lunar_month INT,
    lunar_day INT,
    lunar_leap_month BOOLEAN NOT NULL DEFAULT FALSE,
    note VARCHAR(200),
    event_kind VARCHAR(16) NOT NULL DEFAULT 'IMPORTANT',
    birthday_subject VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    home_reminder_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reminder_enabled BOOLEAN NOT NULL,
    reminder_days_before INT NOT NULL,
    reminder_time TIME,
    notification_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT',
    chat_id VARCHAR(128),
    chat_name VARCHAR(128),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
