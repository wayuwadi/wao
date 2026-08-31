package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FunctionUsageEventEntity;
import cn.wayuwa.wao.domain.FunctionUsageSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FunctionUsageEventMapper extends BaseMapper<FunctionUsageEventEntity> {
    @Select("""
            SELECT feature_id AS featureId, COUNT(*) AS totalOpens,
                   SUM(CASE WHEN opened_at >= #{since} THEN 1 ELSE 0 END) AS recentOpens,
                   MAX(opened_at) AS lastOpenedAt
            FROM function_usage_event
            WHERE member_id = #{memberId}
            GROUP BY feature_id
            """)
    List<FunctionUsageSummary> summarize(
            @Param("memberId") String memberId, @Param("since") LocalDateTime since);
}
