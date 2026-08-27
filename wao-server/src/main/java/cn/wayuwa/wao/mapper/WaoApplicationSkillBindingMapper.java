package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.WaoApplicationSkillBindingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WaoApplicationSkillBindingMapper extends BaseMapper<WaoApplicationSkillBindingEntity> {
    @Delete("DELETE FROM wao_application_skill_binding WHERE application_id=#{applicationId}")
    int deleteByApplication(String applicationId);
}
