package cn.wayuwa.wao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SkillFavoriteMapper {
    @Select("SELECT COUNT(*) FROM skill_favorite WHERE skill_id=#{skillId} AND member_id=#{memberId}")
    int exists(String skillId, String memberId);
    @Insert("INSERT IGNORE INTO skill_favorite(skill_id,member_id,created_at) VALUES(#{skillId},#{memberId},CURRENT_TIMESTAMP(3))")
    int add(String skillId, String memberId);
    @Delete("DELETE FROM skill_favorite WHERE skill_id=#{skillId} AND member_id=#{memberId}")
    int remove(String skillId, String memberId);
}
