-- 一次 AI Run 可以按用户选择顺序关联同一项目的多条任务；历史运行回填为单任务关系。
CREATE TABLE ai_task_run_task (
    run_id      VARCHAR(32) NOT NULL,
    task_id     VARCHAR(32) NOT NULL,
    ord         INT         NOT NULL,
    PRIMARY KEY (run_id, task_id),
    UNIQUE KEY uk_ai_task_run_task_order (run_id, ord),
    KEY idx_ai_task_run_task_task (task_id, run_id),
    CONSTRAINT fk_ai_task_run_task_run FOREIGN KEY (run_id) REFERENCES ai_task_run (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ai_task_run_task (run_id, task_id, ord)
SELECT id, task_id, 0
FROM ai_task_run;
