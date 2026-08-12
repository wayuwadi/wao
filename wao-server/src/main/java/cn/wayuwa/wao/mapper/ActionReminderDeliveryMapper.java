package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.ActionReminderDeliveryEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActionReminderDeliveryMapper extends BaseMapper<ActionReminderDeliveryEntity> {
    @Update("UPDATE action_reminder_delivery SET status = 'PROCESSING', attempts = #{attempts}, "
            + "attempted_at = #{now}, error_message = NULL WHERE id = #{id} "
            + "AND status IN ('FAILED', 'PROCESSING') "
            + "AND attempted_at = #{previousAttempt}")
    int claimRetry(@Param("id") String id, @Param("previousAttempt") LocalDateTime previousAttempt,
            @Param("now") LocalDateTime now, @Param("attempts") int attempts);
}
