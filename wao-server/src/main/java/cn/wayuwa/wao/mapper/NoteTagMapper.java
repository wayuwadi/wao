package cn.wayuwa.wao.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 小记共享标签字典，不依赖是否已有记录使用。 */
@Mapper
public interface NoteTagMapper {
    @Select("SELECT name FROM note_tag ORDER BY name")
    List<String> listNames();

    @Insert("INSERT INTO note_tag (name) VALUES (#{name}) ON DUPLICATE KEY UPDATE name = name")
    int ensureName(@Param("name") String name);
}
