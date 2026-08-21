package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.SkillPublisherEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillPublisherMapper extends BaseMapper<SkillPublisherEntity> {
    @Select("SELECT * FROM skill_publisher WHERE member_id = #{memberId}")
    SkillPublisherEntity findByMember(String memberId);
    @Select("SELECT * FROM skill_publisher WHERE handle = #{handle}")
    SkillPublisherEntity findByHandle(String handle);
}
