-- AI Run 支持多种 AI 工具：节点声明自己能跑哪些工具，运行记录保存本次使用的工具与指定电脑。
ALTER TABLE ai_runner_node
    ADD COLUMN engines VARCHAR(128) NOT NULL DEFAULT 'codex' AFTER platform;

ALTER TABLE ai_project_config
    ADD COLUMN default_engine VARCHAR(24) NOT NULL DEFAULT 'codex' AFTER workspace_dir;

ALTER TABLE ai_task_run
    ADD COLUMN engine VARCHAR(24) NOT NULL DEFAULT 'codex' AFTER project_id,
    ADD COLUMN requested_runner_id VARCHAR(32) NULL AFTER runner_id;
