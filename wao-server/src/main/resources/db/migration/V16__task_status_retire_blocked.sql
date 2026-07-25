-- 任务状态改版：待办 → 进行中 → AI 进行中 → 待验证 → 已完成。
-- 「阻塞」不再是状态（等待他人由 task.waiting 表达），存量数据统一迁到「进行中」。
UPDATE task SET status = '进行中' WHERE status = '阻塞';
