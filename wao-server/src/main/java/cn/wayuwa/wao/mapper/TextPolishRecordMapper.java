package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TextPolishRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 独立润色工作台历史记录 Mapper。 */
@Mapper
public interface TextPolishRecordMapper extends BaseMapper<TextPolishRecordEntity> {
    /** 只返回指定成员最近 50 条记录，成员边界固化在 SQL 而非调用方过滤。 */
    @Select("""
            SELECT id, member_id AS memberId, prompt_snapshot AS promptSnapshot,
                   original_text AS originalText, polished_text AS polishedText, created_at AS createdAt
              FROM text_polish_record
             WHERE member_id = #{memberId}
             ORDER BY created_at DESC
             LIMIT 50
            """)
    List<TextPolishRecordEntity> listOwned(@Param("memberId") String memberId);
}
