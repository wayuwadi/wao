-- Separate revisioned rules cannot be erased by an older full-task client.
CREATE TABLE task_time_rule (
    task_id VARCHAR(32) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    body_json TEXT NOT NULL,
    next_task_id VARCHAR(32) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    error_message VARCHAR(255) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id),
    KEY idx_task_time_active (active, member_id),
    FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A reminder and its private in-app notification commit together.
CREATE TABLE task_time_delivery (
    task_id VARCHAR(32) NOT NULL,
    member_id VARCHAR(64) NOT NULL,
    reminder_key VARCHAR(96) NOT NULL,
    delivered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (task_id, member_id, reminder_key),
    FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
