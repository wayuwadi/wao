package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.PersonalWorkspaceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 个人工作台 Mapper；所有读取和变更都包含成员归属条件。 */
@Mapper
public interface PersonalWorkspaceMapper extends BaseMapper<PersonalWorkspaceEntity> {

    @Select("""
            SELECT * FROM personal_workspace
            WHERE owner_member_id = #{ownerMemberId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<PersonalWorkspaceEntity> listOwned(@Param("ownerMemberId") String ownerMemberId);

    @Select("SELECT * FROM personal_workspace WHERE id = #{id} AND owner_member_id = #{ownerMemberId} LIMIT 1")
    PersonalWorkspaceEntity findOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);

    @Update("""
            UPDATE personal_workspace
            SET project_id = #{projectId}, name = #{name}, description_text = #{description},
                content_json = #{contentJson}, updated_at = #{updatedAt}
            WHERE id = #{id} AND owner_member_id = #{ownerMemberId}
            """)
    int updateOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId,
            @Param("projectId") String projectId, @Param("name") String name,
            @Param("description") String description, @Param("contentJson") String contentJson,
            @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM personal_workspace WHERE id = #{id} AND owner_member_id = #{ownerMemberId}")
    int deleteOwned(@Param("id") String id, @Param("ownerMemberId") String ownerMemberId);
}
