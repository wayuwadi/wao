ALTER TABLE system_periodic_report
    ADD COLUMN aggregation_options_json JSON NULL AFTER run_sources_json;

UPDATE system_periodic_report
SET aggregation_options_json = JSON_ARRAY('TASK_CREATED', 'TASK_COMPLETED', 'AI_RUN')
WHERE aggregation_options_json IS NULL;

ALTER TABLE system_periodic_report
    MODIFY COLUMN aggregation_options_json JSON NOT NULL;
