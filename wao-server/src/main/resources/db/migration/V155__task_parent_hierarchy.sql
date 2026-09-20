-- 保留完整任务身份；删除父任务时子任务回到顶层，不级联删除内容。
ALTER TABLE task
    ADD COLUMN parent_task_id VARCHAR(32) NULL,
    ADD INDEX idx_task_parent (parent_task_id),
    ADD CONSTRAINT fk_task_parent FOREIGN KEY (parent_task_id) REFERENCES task(id) ON DELETE SET NULL;
