package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 小记共享标签字典，不依赖是否已有记录使用。 */
@Mapper
public interface NoteTagMapper {
    @Select("SELECT id, tags_json FROM doc WHERE kb_id = 'kb-notes' AND JSON_CONTAINS(COALESCE(tags_json, JSON_ARRAY()), JSON_QUOTE(#{name})) FOR UPDATE")
    List<cn.wayuwa.wao.domain.DocEntity> taggedNotes(@Param("name") String name);

    @org.apache.ibatis.annotations.Update("UPDATE doc SET tags_json = #{tags}, rev = COALESCE(rev, 0) + 1, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateTags(@Param("id") String id, @Param("tags") String tags);

    @org.apache.ibatis.annotations.Delete("DELETE FROM note_tag WHERE name = #{name}")
    int deleteName(@Param("name") String name);
    @Select("SELECT name FROM note_tag ORDER BY name")
    List<String> listNames();

    @Insert("INSERT INTO note_tag (name) VALUES (#{name}) ON DUPLICATE KEY UPDATE name = name")
    int ensureName(@Param("name") String name);
}
