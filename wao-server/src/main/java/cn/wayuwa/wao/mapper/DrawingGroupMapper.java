package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DrawingGroupMapper {
    record Group(String id, String name) {}
    record Assignment(String fileId, String groupId) {}
    @Select("SELECT id, name FROM drawing_group WHERE member_id=#{member} ORDER BY created_at, id")
    List<Group> groups(@Param("member") String member);
    @Select("SELECT file_id, group_id FROM drawing_group_assignment WHERE member_id=#{member}")
    List<Assignment> assignments(@Param("member") String member);
    @Select("SELECT group_id FROM drawing_group_assignment WHERE member_id=#{member} AND file_id=#{file}")
    String groupOf(@Param("member") String member, @Param("file") String file);
    @Select("SELECT COUNT(*) FROM drawing_group WHERE id=#{id} AND member_id=#{member}")
    int owned(@Param("id") String id, @Param("member") String member);
    @Insert("INSERT INTO drawing_group(id, member_id, name, created_at) VALUES(#{id}, #{member}, #{name}, CURRENT_TIMESTAMP(6))")
    int create(@Param("id") String id, @Param("member") String member, @Param("name") String name);
    @Update("UPDATE drawing_group SET name=#{name} WHERE id=#{id} AND member_id=#{member}")
    int rename(@Param("id") String id, @Param("member") String member, @Param("name") String name);
    @Insert("INSERT INTO drawing_group_assignment(member_id, file_id, group_id) VALUES(#{member}, #{file}, #{group}) ON DUPLICATE KEY UPDATE group_id=VALUES(group_id)")
    int assign(@Param("member") String member, @Param("file") String file, @Param("group") String group);
    @Delete("DELETE FROM drawing_group_assignment WHERE member_id=#{member} AND file_id=#{file}")
    int unassign(@Param("member") String member, @Param("file") String file);
}
