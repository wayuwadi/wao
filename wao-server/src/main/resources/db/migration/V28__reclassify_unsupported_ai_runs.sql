-- 已经完成、合并并上线的 AI Run，不应仅因主动放弃无真实数据源的非必要功能而标记失败。
-- 这类记录的摘要同时包含完成上线事实和“不能/无法伪造实现”的边界说明，因此可安全重分类为成功。
UPDATE ai_task_run
SET status = 'succeeded', exit_code = 0
WHERE status = 'failed'
  AND (result_summary LIKE '%不能伪造实现%' OR result_summary LIKE '%无法伪造实现%')
  AND (result_summary LIKE '%已合并并上线%' OR result_summary LIKE '%生产部署%成功%');

UPDATE task t
JOIN ai_task_run r ON r.task_id = t.id
SET t.status = '待验证'
WHERE t.status = '进行中'
  AND r.status = 'succeeded'
  AND (r.result_summary LIKE '%不能伪造实现%' OR r.result_summary LIKE '%无法伪造实现%')
  AND (r.result_summary LIKE '%已合并并上线%' OR r.result_summary LIKE '%生产部署%成功%');
