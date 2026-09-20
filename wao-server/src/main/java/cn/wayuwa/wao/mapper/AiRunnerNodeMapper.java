package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiRunnerNodeMapper extends BaseMapper<AiRunnerNodeEntity> {

    /** 新鲜的确定认证失败仅用于本机登录态，不能阻断集中账号。 */
    @Select("SELECT COUNT(*) > 0 FROM codex_runner_quota WHERE runner_id = #{id} "
            + "AND last_checked_at >= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE) "
            + "AND (error_message LIKE '%401%' OR error_message LIKE '%invalidated oauth token%' "
            + "OR error_message LIKE '%refresh_token_reused%')")
    boolean hasInvalidLocalCodexAuth(String id);

    /** 用主库时钟刷新节点心跳，避免多 API 节点时钟漂移导致在线状态倒退。 */
    @Update("UPDATE ai_runner_node SET last_seen_at = CURRENT_TIMESTAMP(6) WHERE id = #{id}")
    int touchLastSeenAt(@Param("id") String id);
}
