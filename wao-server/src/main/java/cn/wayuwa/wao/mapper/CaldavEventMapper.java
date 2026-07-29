package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.CaldavEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** CalDAV 事件表 Mapper。 */
@Mapper
public interface CaldavEventMapper extends BaseMapper<CaldavEventEntity> {
}
