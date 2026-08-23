package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.WorkdayRhythmReminderDeliveryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
@Mapper public interface WorkdayRhythmReminderDeliveryMapper extends BaseMapper<WorkdayRhythmReminderDeliveryEntity> {
    @Update("UPDATE workday_rhythm_reminder_delivery SET status='PROCESSING',attempts=#{attempts},attempted_at=#{now} WHERE id=#{id} AND status='FAILED' AND attempted_at=#{previous}")
    int claimRetry(@Param("id") String id, @Param("previous") LocalDateTime previous, @Param("now") LocalDateTime now, @Param("attempts") int attempts);
}
