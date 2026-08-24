package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.ProjectEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 项目表 Mapper。 */
@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
    /** 锁住项目行，保证首次开始推进与团队通知在并发请求下仍幂等。 */
    @Select("SELECT * FROM project WHERE id=#{id} FOR UPDATE")
    ProjectEntity selectByIdForUpdate(String id);
}
