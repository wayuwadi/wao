package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
@Mapper public interface FeishuNotificationMapper extends BaseMapper<FeishuNotificationEntity> {
    /** 同一通知在双节点上串行处理，确保共享摘要保留所有人的最新选择。 */
    @Select("SELECT * FROM feishu_notification WHERE id = #{id} FOR UPDATE")
    FeishuNotificationEntity lockById(@Param("id") String id);
}
