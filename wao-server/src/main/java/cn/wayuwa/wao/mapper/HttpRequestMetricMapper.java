package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.HttpRequestMetricEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** HTTP 体验指标 Mapper；业务查询始终带成员归属条件。 */
@Mapper
public interface HttpRequestMetricMapper extends BaseMapper<HttpRequestMetricEntity> {

    @Select("""
            SELECT * FROM http_request_metric
            WHERE member_id = #{memberId} AND started_at >= #{since}
            ORDER BY started_at DESC, id DESC
            """)
    List<HttpRequestMetricEntity> listOwnedSince(
            @Param("memberId") String memberId, @Param("since") LocalDateTime since);

    @Delete("DELETE FROM http_request_metric WHERE started_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
