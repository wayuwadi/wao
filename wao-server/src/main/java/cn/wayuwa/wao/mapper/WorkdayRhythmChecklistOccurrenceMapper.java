package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistOccurrenceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
@Mapper public interface WorkdayRhythmChecklistOccurrenceMapper extends BaseMapper<WorkdayRhythmChecklistOccurrenceEntity> {
    @Select("SELECT * FROM workday_rhythm_checklist_occurrence WHERE id=#{id} AND member_id=#{memberId}")
    WorkdayRhythmChecklistOccurrenceEntity findOwned(@Param("id") String id, @Param("memberId") String memberId);
}
