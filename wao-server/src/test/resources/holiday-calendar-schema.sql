CREATE TABLE member_preference (
    member_id VARCHAR(32) NOT NULL,
    pref_key VARCHAR(64) NOT NULL,
    pref_value VARCHAR(4000) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (member_id, pref_key)
);

CREATE TABLE holiday_reminder (
    id VARCHAR(32) PRIMARY KEY,
    member_id VARCHAR(32) NOT NULL,
    holiday_code VARCHAR(64) NOT NULL,
    holiday_name VARCHAR(64) NOT NULL,
    reminder_time TIME,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE (member_id, holiday_code)
);

INSERT INTO member_preference (member_id, pref_key, pref_value, updated_at)
VALUES ('wg', 'holiday-international-categories', '["FAMILY"]', CURRENT_TIMESTAMP);
