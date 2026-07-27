-- 一键分发 Runner 脚本更新：记录每台执行电脑自报的脚本哈希，以及后台下发更新指令的时间。
ALTER TABLE ai_runner_node
    ADD COLUMN script_hash VARCHAR(64) NULL AFTER admin_max_concurrency,
    ADD COLUMN update_requested_at DATETIME NULL AFTER script_hash;
