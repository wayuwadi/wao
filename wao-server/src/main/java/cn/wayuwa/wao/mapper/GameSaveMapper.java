package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.GameSaveEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 游戏活动存档 Mapper。 */
@Mapper
public interface GameSaveMapper extends BaseMapper<GameSaveEntity> {

    @Update("""
            UPDATE game_save
            SET state_json = #{save.stateJson}, score = #{save.score}, move_count = #{save.moveCount},
                won = #{save.won}, revision = #{save.revision},
                previous_state_json = #{save.previousStateJson}, previous_score = #{save.previousScore},
                previous_move_count = #{save.previousMoveCount},
                started_at = #{save.startedAt}, updated_at = #{save.updatedAt}
            WHERE id = #{save.id} AND revision = #{expectedRevision}
            """)
    int updateSnapshot(@Param("save") GameSaveEntity save, @Param("expectedRevision") int expectedRevision);
}
