package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.QuotaRefreshNotificationStateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuotaRefreshNotificationStateMapper extends BaseMapper<QuotaRefreshNotificationStateEntity> {
    @Select("SELECT * FROM quota_refresh_notification_state WHERE source_key = #{sourceKey} FOR UPDATE")
    QuotaRefreshNotificationStateEntity selectForUpdate(@Param("sourceKey") String sourceKey);
}
