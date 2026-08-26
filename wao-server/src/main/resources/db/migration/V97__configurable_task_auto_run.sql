ALTER TABLE ai_task_auto_run_state
    ADD COLUMN project_id VARCHAR(32) NULL AFTER idle_hours,
    ADD COLUMN task_statuses_json JSON NOT NULL DEFAULT (JSON_ARRAY('待办')) AFTER project_id,
    ADD COLUMN notification_webhook_id VARCHAR(32) NULL AFTER task_statuses_json;

UPDATE ai_task_auto_run_state s
SET project_id = (
    SELECT p.id
    FROM project p
    WHERE p.name = 'Wa All in One'
    ORDER BY p.id
    LIMIT 1
)
WHERE s.id = 'oldest-task'
  AND s.project_id IS NULL;
