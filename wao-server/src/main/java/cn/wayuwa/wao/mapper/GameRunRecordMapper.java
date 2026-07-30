package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.GameRunRecordEntity;
import cn.wayuwa.wao.domain.GameRunSummary;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 游戏历史战绩 Mapper。 */
@Mapper
public interface GameRunRecordMapper extends BaseMapper<GameRunRecordEntity> {

    @Select("""
            SELECT COUNT(*) AS games_played,
                   COALESCE(MAX(score), 0) AS best_score,
                   COALESCE(MAX(highest_tile), 0) AS highest_tile,
                   COALESCE(SUM(move_count), 0) AS total_moves,
                   COALESCE(SUM(CASE WHEN outcome = 'WON' THEN 1 ELSE 0 END), 0) AS wins
            FROM game_run_record
            WHERE member_id = #{memberId} AND game_key = #{gameKey}
            """)
    GameRunSummary summarize(@Param("memberId") String memberId, @Param("gameKey") String gameKey);
}
