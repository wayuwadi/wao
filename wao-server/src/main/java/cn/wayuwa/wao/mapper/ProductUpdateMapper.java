package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.ProductUpdateRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 保存和分页读取已经成功交付的 Git 产品提交。 */
@Mapper
public interface ProductUpdateMapper {
    @Insert("""
            INSERT IGNORE INTO product_update_event(run_id, commit_sha, scope, commit_subject, committed_at)
            VALUES(#{runId}, #{commitSha}, #{scope}, #{subject}, #{committedAt})
            """)
    int insertIgnore(@Param("runId") String runId, @Param("commitSha") String commitSha,
            @Param("scope") String scope, @Param("subject") String subject,
            @Param("committedAt") LocalDateTime committedAt);

    @Select("SELECT COUNT(DISTINCT DATE(committed_at)) FROM product_update_event")
    long countDays();

    @Select("""
            SELECT DISTINCT DATE(committed_at) AS update_date
              FROM product_update_event
             ORDER BY update_date DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<LocalDate> selectDays(@Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            SELECT scope, commit_subject, committed_at
              FROM product_update_event
             WHERE DATE(committed_at) BETWEEN #{oldestDay} AND #{newestDay}
             ORDER BY committed_at DESC, id DESC
            """)
    List<ProductUpdateRow> selectBetween(
            @Param("oldestDay") LocalDate oldestDay, @Param("newestDay") LocalDate newestDay);
}
