-- 用户在飞书快速收集原消息上添加飞机表情时，以真实 reaction 事件触发一次 AI Run。
-- event_key 同时承担飞书重推和双节点回调的持久幂等；失败事务回滚后允许重新添加表情再派工。
CREATE TABLE feishu_quick_collect_ai_run_trigger (
    event_key          VARCHAR(191) NOT NULL,
    event_id           VARCHAR(191) NULL,
    message_id         VARCHAR(191) NOT NULL,
    operator_open_id   VARCHAR(191) NOT NULL,
    task_id            VARCHAR(32)  NOT NULL,
    ai_run_id          VARCHAR(32)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (event_key),
    KEY idx_quick_collect_ai_run_message (message_id, created_at),
    KEY idx_quick_collect_ai_run_task (task_id, created_at),
    CONSTRAINT fk_quick_collect_ai_run_task FOREIGN KEY (task_id)
        REFERENCES task (id) ON DELETE CASCADE,
    CONSTRAINT fk_quick_collect_ai_run_run FOREIGN KEY (ai_run_id)
        REFERENCES ai_task_run (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
