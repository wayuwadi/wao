-- 项目可配置 AI CLI 执行失败后的同运行自动重试次数；旧项目默认重试两次。
ALTER TABLE ai_project_config
    ADD COLUMN failure_retry_count INT NOT NULL DEFAULT 2 AFTER max_parallel_runs;
