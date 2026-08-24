package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.TaskEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 任务表 Mapper。 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

    /** 飞书原消息编辑只窄更新来源字段，避免覆盖并发保存的任务结构化详情。 */
    @Update("UPDATE task SET title = #{title}, description = #{description} WHERE id = #{id}")
    int updateChatSourceFields(@Param("id") String id, @Param("title") String title,
            @Param("description") String description);
}
