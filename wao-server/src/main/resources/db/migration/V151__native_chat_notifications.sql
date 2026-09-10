CREATE TABLE internal_notification (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    member_id VARCHAR(64) NULL,
    title VARCHAR(255) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    target_path VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL,
    KEY idx_internal_notification_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

UPDATE automation_job SET status = '已暂停', next_fire_at = NULL,
    last_run_label = '已退休：聊天改用 WAO 原生消息流'
WHERE handler = 'shared-chat-nightly-sync';
