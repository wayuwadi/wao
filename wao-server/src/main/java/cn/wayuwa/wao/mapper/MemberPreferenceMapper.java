package cn.wayuwa.wao.mapper;
import cn.wayuwa.wao.domain.MemberPreferenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberPreferenceMapper {

    @Select("SELECT pref_value FROM member_preference WHERE member_id = #{memberId} AND pref_key = #{prefKey}")
    String find(@Param("memberId") String memberId, @Param("prefKey") String prefKey);

    @Insert("INSERT INTO member_preference (member_id, pref_key, pref_value, updated_at) "
            + "VALUES (#{memberId}, #{prefKey}, #{prefValue}, NOW()) "
            + "ON DUPLICATE KEY UPDATE pref_value = #{prefValue}, updated_at = NOW()")
    void upsert(@Param("memberId") String memberId, @Param("prefKey") String prefKey,
            @Param("prefValue") String prefValue);
}
