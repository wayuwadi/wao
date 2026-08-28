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
            SELECT CONCAT('task-created:', t.id) AS event_id, 'TASK_CREATED' AS event_type,
                   t.title, '创建了任务' AS detail, creator.name AS actor_name,
                   t.created_at AS occurred_at,
                   CONCAT('/projects/', t.project_id, '?task=', t.id) AS target_path,
                   'INFO' AS severity
              FROM task t
              LEFT JOIN member creator ON creator.id = t.created_by
             WHERE t.created_at IS NOT NULL
            UNION ALL
            SELECT CONCAT('task-completed:', t.id) AS event_id, 'TASK_COMPLETED' AS event_type,
                   t.title, '完成了任务' AS detail, NULL AS actor_name,
                   t.completed_at AS occurred_at,
                   CONCAT('/projects/', t.project_id, '?task=', t.id) AS target_path,
                   'SUCCESS' AS severity
              FROM task t
             WHERE t.completed_at IS NOT NULL
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
            UNION ALL
            SELECT CONCAT('login:', e.id) AS event_id, 'LOGIN' AS event_type,
                   CONCAT(m.name, '登录了 WAO') AS title,
                   CASE e.provider WHEN 'feishu' THEN '通过飞书登录'
                       WHEN 'wechat' THEN '通过微信登录'
                       WHEN 'email' THEN '通过邮箱登录'
                       ELSE '成功登录' END AS detail,
                   m.name AS actor_name, e.logged_in_at AS occurred_at,
                   NULL AS target_path, 'INFO' AS severity
              FROM account_login_event e
              JOIN member m ON m.id = e.member_id
            """;

    @Select("SELECT events.* FROM (" + EVENT_UNION + ") events "
            + "ORDER BY events.occurred_at DESC, events.event_id DESC LIMIT #{limit}")
    List<NotificationEventRow> recentEvents(@Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM (" + EVENT_UNION + ") events WHERE events.occurred_at > #{since}")
    long countEventsSince(@Param("since") LocalDateTime since);

    /** 读取未读窗口内真实事件，由成员级偏好在服务层做同一套过滤后计数。 */
    @Select("SELECT events.* FROM (" + EVENT_UNION + ") events WHERE events.occurred_at > #{since} "
            + "ORDER BY events.occurred_at DESC, events.event_id DESC")
    List<NotificationEventRow> eventsSince(@Param("since") LocalDateTime since);

    @Select("""
            SELECT m.id AS member_id, m.name AS member_name, m.color,
                   COALESCE(SUM(CASE WHEN e.logged_in_at >= #{sevenDaysAgo} THEN 1 ELSE 0 END), 0) AS count7_days,
                   COALESCE(SUM(CASE WHEN e.logged_in_at >= #{thirtyDaysAgo} THEN 1 ELSE 0 END), 0) AS count30_days,
                   MAX(e.logged_in_at) AS last_login_at
              FROM member m
              LEFT JOIN account_login_event e ON e.member_id = m.id
             GROUP BY m.id, m.name, m.color
             ORDER BY count7_days DESC, last_login_at DESC, m.name
            """)
    List<MemberLoginUsageRow> loginUsage(@Param("sevenDaysAgo") LocalDateTime sevenDaysAgo,
            @Param("thirtyDaysAgo") LocalDateTime thirtyDaysAgo);
}
