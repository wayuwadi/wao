-- 记录每次 AI 运行实际用掉的模型、token 和折算额度。
-- Runner 从 CLI 的结束事件里读出用量后随结果一并回传（见 docs/adr/026-ai-run-usage-accounting.md）。

ALTER TABLE ai_task_run
    ADD COLUMN model VARCHAR(120) NULL COMMENT '本次运行输出 token 最多的模型',
    ADD COLUMN models VARCHAR(500) NULL COMMENT '本次运行用到的全部模型，逗号分隔',
    ADD COLUMN input_tokens BIGINT NULL COMMENT '非缓存输入 token',
    ADD COLUMN output_tokens BIGINT NULL COMMENT '输出 token',
    ADD COLUMN cached_input_tokens BIGINT NULL COMMENT '缓存读取与缓存写入的 token 之和',
    ADD COLUMN total_tokens BIGINT NULL COMMENT '三类 token 的合计',
    ADD COLUMN cost_usd DECIMAL(12, 6) NULL COMMENT '折算额度（美元）；CLI 未回传时为空';

CREATE INDEX idx_ai_run_model ON ai_task_run (model);
