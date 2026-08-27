package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiActivityRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 首页按类型统一分页读取真实 AI 活动。 */
@Mapper
public interface AiActivityMapper {

    @Select("""
            SELECT activity.*
              FROM (
                SELECT r.id, 'FEATURE_DEVELOPMENT' AS activity_type,
                       COALESCE(t.title, '已删除的开发任务') AS title,
                       UPPER(r.status) AS status,
                       COALESCE(p.name, '未归属项目') AS subtitle,
                       CASE r.engine WHEN 'codex' THEN 'Codex' WHEN 'claude' THEN 'Claude Code'
                            WHEN 'qoder' THEN 'Qoder' ELSE r.engine END AS capability_label,
                       r.task_id AS target_id, SUBSTRING_INDEX(r.result_summary, '\n', 1) AS result_preview,
                       r.created_at, r.finished_at
                  FROM ai_task_run r
                  LEFT JOIN task t ON t.id = r.task_id
                  LEFT JOIN project p ON p.id = r.project_id
                 WHERE r.status NOT IN ('queued', 'claimed', 'running')
                UNION ALL
                SELECT cr.id, 'CHAT_REVIEW' AS activity_type, '聊天复盘' AS title,
                       cr.stage AS status,
                       CASE WHEN cr.range_kind = 'HOURS'
                            THEN CONCAT('最近 ', cr.range_value, ' 小时 · ', cr.message_count, ' 条真实消息')
                            WHEN cr.range_kind = 'MESSAGES'
                            THEN CONCAT('最近 ', cr.range_value, ' 条消息 · ', cr.message_count, ' 条真实消息')
                            ELSE CONCAT(cr.start_date, ' 至 ', cr.end_date, ' · ', cr.message_count, ' 条真实消息') END AS subtitle,
                       CASE WHEN cr.ai_capability_id = 'local-codex' THEN '本地 Codex'
                            ELSE COALESCE(account.label, cr.ai_capability_id) END AS capability_label,
                       cr.id AS target_id, cr.error_text AS result_preview,
                       cr.created_at, cr.finished_at
                  FROM chat_review_run cr
                  LEFT JOIN ai_account account ON account.id = cr.ai_capability_id
                 WHERE cr.stage IN ('SUCCEEDED', 'FAILED')
              ) activity
             WHERE (#{type} = 'ALL' OR activity.activity_type = #{type})
             ORDER BY COALESCE(activity.finished_at, activity.created_at) DESC, activity.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<AiActivityRow> page(@Param("type") String type, @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM (
                SELECT r.id, 'FEATURE_DEVELOPMENT' AS activity_type
                  FROM ai_task_run r
                 WHERE r.status NOT IN ('queued', 'claimed', 'running')
                UNION ALL
                SELECT cr.id, 'CHAT_REVIEW' AS activity_type
                  FROM chat_review_run cr
                 WHERE cr.stage IN ('SUCCEEDED', 'FAILED')
            ) activity
            WHERE (#{type} = 'ALL' OR activity.activity_type = #{type})
            """)
    long count(@Param("type") String type);
}
