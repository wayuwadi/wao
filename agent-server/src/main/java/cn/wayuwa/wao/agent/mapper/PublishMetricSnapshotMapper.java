package cn.wayuwa.wao.agent.mapper;

import cn.wayuwa.wao.agent.domain.PublishMetricSnapshotEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PublishMetricSnapshotMapper extends BaseMapper<PublishMetricSnapshotEntity> {
    @Select("""
            SELECT * FROM agent_publish_metric_snapshot
            WHERE publish_task_id = #{taskId} AND workspace_id = #{workspaceId}
            ORDER BY recorded_at DESC LIMIT 20
            """)
    List<PublishMetricSnapshotEntity> recent(@Param("taskId") String taskId,
                                             @Param("workspaceId") String workspaceId);
}
