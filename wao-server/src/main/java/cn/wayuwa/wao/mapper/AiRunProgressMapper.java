package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiRunProgressEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface AiRunProgressMapper extends BaseMapper<AiRunProgressEntity> {
    /** 每个会话只取最近 50 条，长期续聊不会让首页轮询反复加载全部历史正文。 */
    @Select("""
            <script>
            SELECT id, run_id, kind, content, created_at FROM (
              SELECT p.*, ROW_NUMBER() OVER (PARTITION BY run_id ORDER BY created_at DESC, id DESC) AS rn
              FROM ai_run_progress p WHERE run_id IN
              <foreach collection="runIds" item="runId" open="(" separator="," close=")">#{runId}</foreach>
            ) recent WHERE rn &lt;= 50 ORDER BY created_at, id
            </script>
            """)
    List<AiRunProgressEntity> selectRecentForRuns(@Param("runIds") List<String> runIds);
}
