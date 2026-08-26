package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.ProjectSkillBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectSkillBindingMapper extends BaseMapper<ProjectSkillBindingEntity> {
    @Delete("DELETE FROM project_skill_binding WHERE project_id=#{projectId}")
    int deleteByProject(String projectId);
}
