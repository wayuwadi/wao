package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.WorkdayRhythmChecklistItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
@Mapper public interface WorkdayRhythmChecklistItemMapper extends BaseMapper<WorkdayRhythmChecklistItemEntity> {
    @Select("SELECT * FROM workday_rhythm_checklist_item WHERE id=#{id} AND member_id=#{memberId} AND deleted_at IS NULL")
    WorkdayRhythmChecklistItemEntity findOwned(@Param("id") String id, @Param("memberId") String memberId);
    @Select("SELECT DISTINCT member_id FROM workday_rhythm_checklist_item WHERE active=1 AND deleted_at IS NULL AND reminder_time IS NOT NULL AND (notify_direct=1 OR notify_chat=1 OR notify_webhook=1)")
    List<String> reminderMembers();
}
