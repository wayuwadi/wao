package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.HolidayReminderDeliveryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HolidayReminderDeliveryMapper extends BaseMapper<HolidayReminderDeliveryEntity> {
    @Update("UPDATE holiday_reminder_delivery SET status = 'PROCESSING', attempts = #{attempts}, "
            + "attempted_at = #{now}, error_message = NULL WHERE id = #{id} "
            + "AND attempted_at = #{previousAttempt} AND status <> 'SENT'")
    int claimRetry(@Param("id") String id, @Param("previousAttempt") LocalDateTime previousAttempt,
            @Param("now") LocalDateTime now, @Param("attempts") int attempts);
}
