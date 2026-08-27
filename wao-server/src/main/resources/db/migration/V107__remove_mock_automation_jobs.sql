-- V8 写入的四条自动化及其运行记录只有描述性 steps，没有真实 handler 或可靠数据源。
-- 精确按固定种子 id 删除，不触碰后来新增的真实自动化或成员产生的数据。
DELETE FROM automation_run
WHERE job_id IN (
    SELECT id
    FROM automation_job
    WHERE id IN ('j1', 'j2', 'j3', 'j4')
      AND handler IS NULL
);

DELETE FROM automation_job
WHERE id IN ('j1', 'j2', 'j3', 'j4')
  AND handler IS NULL;
