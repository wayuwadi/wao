-- 飞书事件会重试，且 Linux / Windows 节点可能同时收到回调。
-- 以事件 id（无事件 id 时用消息 id）固化收集结果，避免重复创建任务。
CREATE TABLE feishu_quick_collect_event (
    event_key       VARCHAR(191) NOT NULL,
    event_id        VARCHAR(191) NULL,
    message_id      VARCHAR(191) NULL,
    chat_id         VARCHAR(191) NULL,
    sender_open_id  VARCHAR(191) NULL,
    capture_kind    VARCHAR(16)  NULL,
    status          VARCHAR(16)  NOT NULL,
    target_id       VARCHAR(32)  NULL,
    error_message   VARCHAR(512) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_key),
    KEY idx_quick_collect_created (created_at),
    KEY idx_quick_collect_target (target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
