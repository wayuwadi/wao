-- 基于报错重跑：一次运行可以指明自己是在修哪一次失败的运行。
-- Runner 领取时会把上一次的失败摘要一并带给 AI，让它接着报错往下修，而不是从零重来
-- （见 docs/adr/025-ai-run-deploy-gate-and-rerun.md）。

ALTER TABLE ai_task_run
    ADD COLUMN retry_of_run_id VARCHAR(32) NULL COMMENT '本次重跑要修复的那一次失败运行 id';

CREATE INDEX idx_ai_run_retry_of ON ai_task_run (retry_of_run_id);
