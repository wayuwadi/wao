CREATE TABLE personal_routine (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    member_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    body_json LONGTEXT NOT NULL,
    KEY idx_routine_owner (member_id, kind),
    KEY idx_routine_reminder (status, reminder_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE habit_checkin (
    habit_id VARCHAR(32) NOT NULL,
    checkin_date DATE NOT NULL,
    revision BIGINT NOT NULL,
    amount INT NOT NULL DEFAULT 0,
    target INT NOT NULL DEFAULT 1,
    note TEXT NOT NULL,
    PRIMARY KEY (habit_id, checkin_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE personal_routine_delivery (
    routine_id VARCHAR(32) NOT NULL,
    delivery_date DATE NOT NULL,
    slot VARCHAR(16) NOT NULL,
    PRIMARY KEY (routine_id, delivery_date, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
