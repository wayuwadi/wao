package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.MemberLoginUsageRow;
import cn.wayuwa.wao.domain.NotificationEventRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 通知中心只读聚合查询，不复制已有任务与 AI Run 事实。 */
@Mapper
public interface NotificationCenterMapper {
    String EVENT_UNION = """
            SELECT n.id AS event_id, 'INTERNAL' AS event_type, n.title, n.body AS detail,
                   '系统运维' AS actor_name, n.created_at AS occurred_at,
                   n.target_path, 'INFO' AS severity
              FROM internal_notification n
             WHERE n.member_id = #{memberId} AND n.id LIKE 'ops:%'
            UNION ALL
            SELECT CONCAT('mention-chat:', c.id) AS event_id, 'INTERNAL' AS event_type,
                   '聊天中有人 @ 你' AS title, c.content AS detail, sender.name AS actor_name,
                   c.created_at AS occurred_at, '/?chat=1' AS target_path, 'INFO' AS severity
              FROM shared_chat_message c
              JOIN member recipient ON recipient.id = #{memberId}
              LEFT JOIN member sender ON sender.id = c.member_id
             WHERE c.source = 'WAO' AND c.deleted = 0 AND c.member_id <> #{memberId}
               AND LOCATE(CONCAT('@', recipient.name), c.content) > 0
               AND SUBSTRING(c.content, LOCATE(CONCAT('@', recipient.name), c.content) + CHAR_LENGTH(recipient.name) + 1) REGEXP '^([[:space:][:punct:]]|$)'
            UNION ALL
            SELECT CONCAT('mention-comment:', t.id, ':', comment.ordinality) AS event_id,
                   'INTERNAL' AS event_type, t.title,
                   comment.text AS detail, sender.name AS actor_name,
                   STR_TO_DATE(LEFT(REPLACE(comment.time, 'T', ' '), 19), '%Y-%m-%d %H:%i:%s') AS occurred_at,
                   CONCAT('/projects/', t.project_id, '?task=', t.id) AS target_path, 'INFO' AS severity
              FROM task t
              JOIN project p ON p.id = t.project_id
              JOIN member recipient ON recipient.id = #{memberId}
              JOIN JSON_TABLE(t.detail_json, '$.comments[*]' COLUMNS (
                   ordinality FOR ORDINALITY, who VARCHAR(64) PATH '$.who',
                   text TEXT PATH '$.text', time VARCHAR(64) PATH '$.time')) AS comment
              LEFT JOIN member sender ON sender.id = comment.who
             WHERE comment.who <> #{memberId}
               AND LOCATE(CONCAT('@', recipient.name), comment.text) > 0
               AND SUBSTRING(comment.text, LOCATE(CONCAT('@', recipient.name), comment.text) + CHAR_LENGTH(recipient.name) + 1) REGEXP '^([[:space:][:punct:]]|$)'
               AND (p.owner_id = #{memberId} OR JSON_CONTAINS(p.detail_json, JSON_QUOTE(#{memberId}), '$.members'))
            UNION ALL
            SELECT CONCAT('ai-run:', r.id) AS event_id, 'AI_RUN' AS event_type,
                   COALESCE(t.title, '已删除的开发任务') AS title,
                   CASE r.status WHEN 'succeeded' THEN 'AI 运行成功'
                       WHEN 'failed' THEN 'AI 运行失败'
                       WHEN 'cancelled' THEN 'AI 运行已取消'
                       ELSE CONCAT('AI 运行结束：', r.status) END AS detail,
                   requester.name AS actor_name, r.finished_at AS occurred_at,
                   CONCAT('/projects/', r.project_id, '?task=', r.task_id, '&run=', r.id) AS target_path,
                   CASE WHEN r.status = 'failed' THEN 'WARNING'
                       WHEN r.status = 'succeeded' THEN 'SUCCESS' ELSE 'INFO' END AS severity
              FROM ai_task_run r
              LEFT JOIN task t ON t.id = r.task_id
              LEFT JOIN member requester ON requester.id = r.requested_by
             WHERE r.finished_at IS NOT NULL
               AND r.status NOT IN ('queued', 'claimed', 'running')
               AND r.requested_by = #{memberId}
            """;

    @Select("SELECT events.* FROM (" + EVENT_UNION + ") events "
            + "ORDER BY events.occurred_at DESC, events.event_id DESC LIMIT #{limit}")
    List<NotificationEventRow> recentEvents(@Param("limit") int limit, @Param("memberId") String memberId);

    @Select("SELECT COUNT(*) FROM (" + EVENT_UNION + ") events WHERE events.occurred_at > #{since}")
    long countEventsSince(@Param("since") LocalDateTime since, @Param("memberId") String memberId);

    /** 读取未读窗口内真实事件，由成员级偏好在服务层做同一套过滤后计数。 */
    @Select("SELECT events.* FROM (" + EVENT_UNION + ") events WHERE events.occurred_at > #{since} "
            + "ORDER BY events.occurred_at DESC, events.event_id DESC")
    List<NotificationEventRow> eventsSince(@Param("since") LocalDateTime since, @Param("memberId") String memberId);

    @Select("""
            SELECT m.id AS member_id, m.name AS member_name, m.color,
                   COALESCE(SUM(CASE WHEN e.logged_in_at >= #{sevenDaysAgo} THEN 1 ELSE 0 END), 0) AS count7_days,
                   COALESCE(SUM(CASE WHEN e.logged_in_at >= #{thirtyDaysAgo} THEN 1 ELSE 0 END), 0) AS count30_days,
                   MAX(e.logged_in_at) AS last_login_at
              FROM member m
              LEFT JOIN account_login_event e ON e.member_id = m.id
             WHERE m.directory_visible = 1
             GROUP BY m.id, m.name, m.color
             ORDER BY count7_days DESC, last_login_at DESC, m.name
            """)
    List<MemberLoginUsageRow> loginUsage(@Param("sevenDaysAgo") LocalDateTime sevenDaysAgo,
            @Param("thirtyDaysAgo") LocalDateTime thirtyDaysAgo);
}
