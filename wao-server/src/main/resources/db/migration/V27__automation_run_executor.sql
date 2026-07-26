-- 记录手动触发人；定时运行留空并由界面显示为「定时任务」。
ALTER TABLE automation_run
    ADD COLUMN executor_id VARCHAR(32) NULL COMMENT '手动触发此运行的成员 id；定时运行为空' AFTER result;
