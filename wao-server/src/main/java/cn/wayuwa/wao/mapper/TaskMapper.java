package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 任务表 Mapper。 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
}
