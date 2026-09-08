package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** 以只读聚合查询盘点 WAO 已有数据，不复制任何领域正文。 */
@Mapper
public interface DataCenterMapper {

    /**
     * 返回每个稳定数据源的记录量和最近写入时间。
     *
     * @return 九类数据源的聚合快照
     */
    @Select("""
            SELECT 'projects' AS source_id, COUNT(*) AS record_count,
                   CAST(NULL AS DATETIME) AS latest_at
              FROM project
            UNION ALL
            SELECT 'tasks', COUNT(*),
                   CASE
                     WHEN MAX(created_at) IS NULL THEN MAX(completed_at)
                     WHEN MAX(completed_at) IS NULL THEN MAX(created_at)
                     ELSE GREATEST(MAX(created_at), MAX(completed_at))
                   END
              FROM task
            UNION ALL
            SELECT 'ai-runs', COUNT(*),
                   CASE
                     WHEN MAX(created_at) IS NULL THEN MAX(finished_at)
                     WHEN MAX(finished_at) IS NULL THEN MAX(created_at)
                     ELSE GREATEST(MAX(created_at), MAX(finished_at))
                   END
              FROM ai_task_run
            UNION ALL
            SELECT 'knowledge-documents', COUNT(*), MAX(COALESCE(updated_at, created_at))
              FROM doc
             WHERE deleted_at IS NULL
            UNION ALL
            SELECT 'memory-revisions', COUNT(*), MAX(created_at)
              FROM memory_revision
            UNION ALL
            SELECT 'shared-chat-messages', COUNT(*), MAX(created_at)
              FROM shared_chat_message
            UNION ALL
            SELECT 'http-request-metrics', COUNT(*), MAX(started_at)
              FROM http_request_metric
            UNION ALL
            SELECT 'product-updates', COUNT(*), MAX(committed_at)
              FROM product_update_event
            UNION ALL
            SELECT 'git-repositories', COUNT(*), MAX(updated_at)
              FROM ai_project_config
             WHERE repo_url IS NOT NULL AND TRIM(repo_url) <> ''
            """)
    @Results({
            @Result(column = "source_id", property = "sourceId"),
            @Result(column = "record_count", property = "recordCount"),
            @Result(column = "latest_at", property = "latestAt")
    })
    List<DataCenterSnapshotRow> snapshots();
}
