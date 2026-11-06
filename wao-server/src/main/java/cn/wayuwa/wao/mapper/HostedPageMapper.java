package cn.wayuwa.wao.mapper;

import java.util.Collection;
import java.util.List;
import cn.wayuwa.wao.domain.HostedPageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HostedPageMapper extends BaseMapper<HostedPageEntity> {
    @Select("SELECT id, slug, title, file_name, source_type, project_id, icon_content_type, created_by, created_at, updated_at, revision "
            + "FROM hosted_page WHERE #{sourceType} = 'ALL' OR source_type = #{sourceType} ORDER BY updated_at DESC, id DESC")
    List<HostedPageEntity> listMeta(@Param("sourceType") String sourceType);

    @Select({"<script>",
            "SELECT id, slug, title, file_name, source_type, project_id, icon_content_type, created_by, created_at, updated_at, revision",
            "FROM hosted_page WHERE (#{sourceType} = 'ALL' OR source_type = #{sourceType}) AND (",
            "<choose><when test='projectId != null'>project_id = #{projectId}</when><otherwise>",
            "project_id IS NULL <if test='projectIds != null and !projectIds.isEmpty()'>OR project_id IN",
            "<foreach collection='projectIds' item='project' open='(' separator=',' close=')'>#{project}</foreach></if>",
            "</otherwise></choose>) ORDER BY updated_at DESC, id DESC", "</script>"})
    List<HostedPageEntity> listVisibleMeta(@Param("sourceType") String sourceType,
            @Param("projectIds") java.util.Set<String> projectIds, @Param("projectId") String projectId);
    @Select({
            "<script>",
            "SELECT id, slug, title, file_name, source_type, project_id, icon_content_type, created_by, created_at, updated_at, revision",
            "FROM hosted_page WHERE id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"
    })
    List<HostedPageEntity> listMetaByIds(@Param("ids") Collection<String> ids);
    @Select("SELECT id, slug, title, file_name, source_type, project_id, icon_content_type, created_by, created_at, updated_at, revision "
            + "FROM hosted_page WHERE id = #{id}")
    HostedPageEntity findMetaById(@Param("id") String id);
    @Select("SELECT * FROM hosted_page WHERE slug = #{slug} LIMIT 1")
    HostedPageEntity findBySlug(@Param("slug") String slug);
    @Update({"<script>",
            "UPDATE hosted_page SET slug = #{page.slug}, title = #{page.title},",
            "<if test='updateHtml'>file_name = #{page.fileName}, html_content = #{page.htmlContent},</if>",
            "<if test='updateIcon'>icon_content = #{page.iconContent}, icon_content_type = #{page.iconContentType},</if>",
            "updated_at = #{page.updatedAt}, revision = revision + 1 WHERE id = #{page.id}",
            "<if test='expectedRevision != null'>AND revision = #{expectedRevision}</if>", "</script>"})
    int updateDetails(@Param("page") HostedPageEntity entity, @Param("updateHtml") boolean updateHtml,
            @Param("updateIcon") boolean updateIcon, @Param("expectedRevision") Long expectedRevision);

    @Update({"<script>",
            "UPDATE hosted_page SET file_name = #{page.fileName}, html_content = #{page.htmlContent},",
            "updated_at = #{page.updatedAt}, revision = revision + 1 WHERE id = #{page.id}",
            "<if test='expectedRevision != null'>AND revision = #{expectedRevision}</if>", "</script>"})
    int replaceContent(@Param("page") HostedPageEntity entity, @Param("expectedRevision") Long expectedRevision);

    /** Report refreshes preserve the latest human title, URL and icon, including concurrent edits. */
    @Update("UPDATE hosted_page SET file_name = #{fileName}, html_content = #{htmlContent}, "
            + "project_id = CASE WHEN project_id IS NULL OR project_id = '' THEN #{projectId} ELSE project_id END, "
            + "updated_at = #{updatedAt}, revision = revision + 1 WHERE id = #{id}")
    int replaceReportContent(HostedPageEntity entity);
}
