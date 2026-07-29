-- 定时飞书通知的真实回执。未点击不写记录，正好代表「空」。
CREATE TABLE feishu_notification (
    id VARCHAR(48) NOT NULL, job_id VARCHAR(32) NOT NULL, sent_at DATETIME NOT NULL,
    PRIMARY KEY (id), KEY idx_notification_job_time (job_id, sent_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE feishu_notification_feedback (
    id              VARCHAR(48) NOT NULL,
    notification_id VARCHAR(48) NOT NULL,
    job_id          VARCHAR(32) NOT NULL,
    notification_at DATETIME    NOT NULL,
    action          VARCHAR(16) NOT NULL,
    operator_open_id VARCHAR(128) NOT NULL,
    operator_name   VARCHAR(128),
    acted_at        DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feedback_notification_operator (notification_id, operator_open_id),
    KEY idx_feedback_time (notification_at),
    KEY idx_feedback_job_time (job_id, notification_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
