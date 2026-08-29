package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.FoodWorkspaceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 饮食管理工作区持久化。 */
@Mapper
public interface FoodWorkspaceMapper extends BaseMapper<FoodWorkspaceEntity> {
    /** 原子写透成员唯一快照，避免首次保存的并发插入竞态。 */
    @Insert("""
            INSERT INTO food_workspace (member_id, content_json, created_at, updated_at)
            VALUES (#{memberId}, #{contentJson}, NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE content_json = #{contentJson}, updated_at = NOW(6)
            """)
    void upsert(@Param("memberId") String memberId, @Param("contentJson") String contentJson);
}
