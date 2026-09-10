package cn.wayuwa.wao.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 双节点共享的飞书请求统计，不记录请求参数、身份或凭据。 */
@Mapper
public interface FeishuApiUsageMapper {
    @Insert("""
        INSERT INTO feishu_api_usage
            (usage_day, endpoint, request_count, first_observed_at, last_observed_at)
        VALUES (#{day}, #{endpoint}, 1, #{observedAt}, #{observedAt})
        ON DUPLICATE KEY UPDATE request_count = request_count + 1,
            first_observed_at = LEAST(first_observed_at, #{observedAt}),
            last_observed_at = GREATEST(last_observed_at, #{observedAt})
        """)
    void record(@Param("day") LocalDate day, @Param("endpoint") String endpoint,
            @Param("observedAt") LocalDateTime observedAt);

    @Select("""
        SELECT endpoint, SUM(request_count) AS requests,
            MIN(first_observed_at) AS first_observed_at, MAX(last_observed_at) AS last_observed_at
        FROM feishu_api_usage WHERE usage_day >= #{start} AND usage_day <= #{end}
        GROUP BY endpoint ORDER BY requests DESC, endpoint
        """)
    List<Usage> ranking(@Param("start") LocalDate start, @Param("end") LocalDate end);

    record Usage(String endpoint, long requests, LocalDateTime firstObservedAt, LocalDateTime lastObservedAt) { }
}
