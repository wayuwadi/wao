-- AI Run 区分领取到终态的总耗时与 AI CLI 进程的真实执行耗时。
ALTER TABLE ai_task_run
    ADD COLUMN ai_execution_duration_ms BIGINT NULL
        COMMENT 'Runner 单调时钟累计的 AI CLI 实际执行毫秒数；旧记录为空'
        AFTER total_tokens;
