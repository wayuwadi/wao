package cn.wayuwa.wao.mapper;
import java.util.List;
import cn.wayuwa.wao.domain.MemberPreferenceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MemberPreferenceMapper {

    @Update("UPDATE member_preference SET pref_value = #{value}, updated_at = NOW() "
            + "WHERE member_id = #{memberId} AND pref_key = #{key} "
            + "AND CAST(pref_value AS BINARY) = CAST(#{expected} AS BINARY)")
    int compareAndSet(@Param("memberId") String memberId, @Param("key") String key,
            @Param("expected") String expected, @Param("value") String value);

    @Select("SELECT pref_value FROM member_preference WHERE member_id = #{memberId} AND pref_key = #{prefKey}")
    String find(@Param("memberId") String memberId, @Param("prefKey") String prefKey);

    @Select("SELECT member_id AS memberId, pref_key AS prefKey, pref_value AS prefValue, "
            + "updated_at AS updatedAt FROM member_preference "
            + "WHERE member_id = #{memberId} AND pref_key >= #{fromKey} AND pref_key <= #{toKey} "
            + "ORDER BY pref_key DESC")
    List<MemberPreferenceEntity> findRange(@Param("memberId") String memberId,
            @Param("fromKey") String fromKey, @Param("toKey") String toKey);

    @Insert("INSERT INTO member_preference (member_id, pref_key, pref_value, updated_at) "
            + "VALUES (#{memberId}, #{prefKey}, #{prefValue}, NOW()) "
            + "ON DUPLICATE KEY UPDATE pref_value = #{prefValue}, updated_at = NOW()")
    void upsert(@Param("memberId") String memberId, @Param("prefKey") String prefKey,
            @Param("prefValue") String prefValue);
}
