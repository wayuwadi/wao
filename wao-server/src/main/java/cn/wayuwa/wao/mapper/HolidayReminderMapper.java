package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.HolidayReminderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface HolidayReminderMapper extends BaseMapper<HolidayReminderEntity> {
    @Select("SELECT * FROM holiday_reminder WHERE member_id = #{memberId} AND holiday_code = #{code} LIMIT 1")
    HolidayReminderEntity find(@Param("memberId") String memberId, @Param("code") String code);
}
