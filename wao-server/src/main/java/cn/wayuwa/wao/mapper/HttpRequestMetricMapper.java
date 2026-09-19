package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.HttpRequestMetricEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** HTTP 体验指标 Mapper；业务查询始终带成员归属条件。 */
@Mapper
public interface HttpRequestMetricMapper extends BaseMapper<HttpRequestMetricEntity> {

    /** 已完整校验的最多 100 条指标，用一次数据库往返写入；成员身份由服务层绑定。 */
    @Insert("""
            <script>
            INSERT INTO http_request_metric
              (member_id, method, request_path, status_code, duration_ms, network_type,
               retry_attempt, started_at, created_at)
            VALUES
            <foreach collection="metrics" item="metric" separator=",">
              (#{metric.memberId}, #{metric.method}, #{metric.requestPath}, #{metric.statusCode},
               #{metric.durationMs}, #{metric.networkType}, #{metric.retryAttempt},
               #{metric.startedAt}, #{metric.createdAt})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("metrics") List<HttpRequestMetricEntity> metrics);

    @Select("""
            SELECT * FROM http_request_metric
            WHERE member_id = #{memberId} AND started_at >= #{since}
            ORDER BY started_at DESC, id DESC
            """)
    List<HttpRequestMetricEntity> listOwnedSince(
            @Param("memberId") String memberId, @Param("since") LocalDateTime since);

    @Delete("DELETE FROM http_request_metric WHERE member_id = #{memberId}")
    int deleteOwned(@Param("memberId") String memberId);

    @Delete("DELETE FROM http_request_metric WHERE started_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
