-- 创建人必须由服务端会话写入；历史任务没有可信来源，保持 NULL，不能用负责人冒充。
ALTER TABLE task ADD COLUMN created_by VARCHAR(32) NULL AFTER owner_id;
CREATE INDEX idx_task_created_by ON task (created_by);
