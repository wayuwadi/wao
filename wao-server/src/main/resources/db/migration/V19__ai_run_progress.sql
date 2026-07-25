-- AI 运行实时进展：运行表保存最新摘要供首页快速展示，明细表用于任务内长期回看。
ALTER TABLE ai_task_run
    ADD COLUMN latest_progress VARCHAR(1000) NULL AFTER result_summary,
    ADD COLUMN progress_updated_at DATETIME NULL AFTER latest_progress;

CREATE TABLE ai_run_progress (
    id          VARCHAR(32)   NOT NULL,
    run_id      VARCHAR(32)   NOT NULL,
    kind        VARCHAR(24)   NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    created_at  DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_progress_run_created (run_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
