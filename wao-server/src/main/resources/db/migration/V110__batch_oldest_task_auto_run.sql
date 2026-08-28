ALTER TABLE ai_task_auto_run_state
    ADD COLUMN batch_size INT NOT NULL DEFAULT 2 AFTER idle_hours,
    ADD CONSTRAINT chk_task_auto_run_batch_size CHECK (batch_size BETWEEN 1 AND 10);

UPDATE ai_task_auto_run_state s
JOIN ai_project_config c ON c.project_id = s.project_id
SET s.batch_size = LEAST(GREATEST(c.max_parallel_runs, 1), 10)
WHERE s.id = 'oldest-task';
