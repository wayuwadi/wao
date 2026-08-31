-- 项目级 AI Run 任务理解提示词；旧工作流由服务端返回并使用安全默认版本。
ALTER TABLE ai_project_config
    ADD COLUMN execution_prompt TEXT NULL AFTER failure_retry_count;
