package cn.wayuwa.wao.mapper;

import java.time.LocalDateTime;

import cn.wayuwa.wao.domain.DocTemplateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DocTemplateMapper extends BaseMapper<DocTemplateEntity> {

    /** 仅当客户端读到的更新时间仍为当前版本时保存，避免旧草稿静默覆盖其他窗口的新内容。 */
    @Update("""
            UPDATE doc_template
               SET name = #{template.name}, description = #{template.description},
                   blocks_json = #{template.blocksJson}, content_json = #{template.contentJson},
                   content_markdown = #{template.contentMarkdown}, content_version = #{template.contentVersion},
                   updated_at = #{template.updatedAt}
             WHERE id = #{template.id} AND created_by = #{template.createdBy}
               AND updated_at = #{expectedUpdatedAt}
            """)
    int updateIfUnchanged(@Param("template") DocTemplateEntity template,
            @Param("expectedUpdatedAt") LocalDateTime expectedUpdatedAt);
}
