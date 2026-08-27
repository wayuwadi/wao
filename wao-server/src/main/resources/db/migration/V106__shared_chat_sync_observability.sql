-- V106：共享聊天同步性能与可观测性，包括内容指纹、双节点租约、当日核对运行和 Runner 资源快照。
ALTER TABLE shared_chat_message
    ADD COLUMN feishu_source_hash CHAR(64) NULL AFTER feishu_message_id,
    ADD KEY idx_shared_chat_feishu_hash (feishu_message_id, feishu_source_hash);

ALTER TABLE ai_runner_node
    ADD COLUMN cpu_cores INT NULL AFTER last_memory_collection_error,
    ADD COLUMN cpu_usage_percent DECIMAL(5,1) NULL AFTER cpu_cores,
    ADD COLUMN memory_total_bytes BIGINT NULL AFTER cpu_usage_percent,
    ADD COLUMN memory_available_bytes BIGINT NULL AFTER memory_total_bytes,
    ADD COLUMN disk_total_bytes BIGINT NULL AFTER memory_available_bytes,
    ADD COLUMN disk_free_bytes BIGINT NULL AFTER disk_total_bytes,
    ADD COLUMN disk_root VARCHAR(3) NULL AFTER disk_free_bytes,
    ADD COLUMN system_metrics_collected_at DATETIME(6) NULL AFTER disk_root;

CREATE TABLE shared_chat_sync_state (
    id                      VARCHAR(16)  NOT NULL,
    chat_id                 VARCHAR(128) NULL,
    cursor_epoch_seconds    BIGINT       NOT NULL,
    lease_owner             VARCHAR(32)  NULL,
    lease_until             DATETIME(6)  NULL,
    last_success_at         DATETIME(6)  NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO shared_chat_sync_state (id, cursor_epoch_seconds)
VALUES ('realtime', UNIX_TIMESTAMP()), ('edits', UNIX_TIMESTAMP()), ('daily', UNIX_TIMESTAMP());

CREATE TABLE shared_chat_sync_run (
    id                  VARCHAR(32)  NOT NULL,
    trigger_type        VARCHAR(16)  NOT NULL,
    chat_id             VARCHAR(128) NOT NULL,
    chat_name           VARCHAR(255) NOT NULL,
    sync_day            DATE         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    messages_scanned    INT          NOT NULL DEFAULT 0,
    messages_inserted   INT          NOT NULL DEFAULT 0,
    messages_updated    INT          NOT NULL DEFAULT 0,
    content_bytes_added BIGINT       NOT NULL DEFAULT 0,
    error_message       VARCHAR(500) NULL,
    started_at          DATETIME(6)  NOT NULL,
    finished_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_shared_chat_sync_run_started (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO automation_job
    (id, name, icon, schedule_label, cron_expr, status, last_run_label, description, source, target,
     steps_json, next_fire_at, ord, handler)
VALUES
    ('job-shared-chat-nightly-sync', '共享聊天夜间核对', 'ri-chat-history-line',
     '每日 00:10', '0 10 0 * * *', '正常', '尚未运行',
     '按上海时间核对前一日飞书群消息，内容未变化时不重复下载图片，并记录本次新增正文与图片字节。',
     '飞书共享聊天 · 前一日', 'WAO 共享聊天 · 同步运行',
     JSON_ARRAY('读取前一日真实群消息', '按飞书消息指纹幂等新增或更新', '记录扫描数、变更数与可归因存储增量'),
     DATE_ADD(DATE_ADD(CURDATE(), INTERVAL IF(CURTIME() < '00:10:00', 0, 1) DAY), INTERVAL 10 MINUTE),
     115, 'shared-chat-nightly-sync');
