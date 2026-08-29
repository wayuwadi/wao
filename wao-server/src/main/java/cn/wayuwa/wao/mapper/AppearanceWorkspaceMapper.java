package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AppearanceWorkspaceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 形象管理工作区持久化。 */
@Mapper
public interface AppearanceWorkspaceMapper extends BaseMapper<AppearanceWorkspaceEntity> {
    /** 原子写透成员唯一快照，避免先查再插的竞态。 */
    @Insert("""
            INSERT INTO appearance_workspace (member_id, content_json, created_at, updated_at)
            VALUES (#{memberId}, #{contentJson}, NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE content_json = #{contentJson}, updated_at = NOW(6)
            """)
    void upsert(@Param("memberId") String memberId, @Param("contentJson") String contentJson);
}
