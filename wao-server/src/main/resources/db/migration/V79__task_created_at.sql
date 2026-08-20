-- 任务关联选择器需要按真实创建时间倒序，不能再依赖数据库未定义的返回顺序。
-- 现有线上任务 id 以 `t` + 13 位 Unix 毫秒开头（前端幂等 PUT id 末尾还带序号），可无损回填；更早的种子任务没有
-- 可靠时间来源，保持 NULL 并在选择器中稳定排到最后，不伪造创建时间。
ALTER TABLE task
    ADD COLUMN created_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER created_by;

UPDATE task
SET created_at = NULL
WHERE id NOT REGEXP '^t[0-9]{13}[0-9a-z]*$';

UPDATE task
SET created_at = FROM_UNIXTIME(CAST(SUBSTRING(id, 2, 13) AS UNSIGNED) / 1000)
WHERE id REGEXP '^t[0-9]{13}[0-9a-z]*$';
