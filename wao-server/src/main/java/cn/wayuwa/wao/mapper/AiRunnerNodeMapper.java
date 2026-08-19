package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiRunnerNodeMapper extends BaseMapper<AiRunnerNodeEntity> {

    /** 用主库时钟刷新节点心跳，避免多 API 节点时钟漂移导致在线状态倒退。 */
    @Update("UPDATE ai_runner_node SET last_seen_at = CURRENT_TIMESTAMP(6) WHERE id = #{id}")
    int touchLastSeenAt(@Param("id") String id);
}
