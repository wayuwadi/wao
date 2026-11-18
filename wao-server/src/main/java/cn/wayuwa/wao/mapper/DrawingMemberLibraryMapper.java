package cn.wayuwa.wao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DrawingMemberLibraryMapper {
    @Select("SELECT items_json FROM drawing_member_library WHERE member_id = #{memberId}")
    String find(String memberId);

    @Insert("INSERT IGNORE INTO drawing_member_library(member_id, items_json) VALUES (#{memberId}, '[]')")
    void ensure(String memberId);

    @Select("SELECT items_json FROM drawing_member_library WHERE member_id = #{memberId} FOR UPDATE")
    String lock(String memberId);

    @Update("UPDATE drawing_member_library SET items_json = #{itemsJson} WHERE member_id = #{memberId}")
    void save(@Param("memberId") String memberId, @Param("itemsJson") String itemsJson);
}
