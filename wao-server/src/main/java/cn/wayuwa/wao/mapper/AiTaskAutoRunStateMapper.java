package cn.wayuwa.wao.mapper;

import cn.wayuwa.wao.domain.AiTaskAutoRunStateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AiTaskAutoRunStateMapper extends BaseMapper<AiTaskAutoRunStateEntity> {

    /** 用数据库时钟抢占五分钟检查窗口；双节点同时轮询时只有一个节点成功。 */
    @Update("""
            UPDATE ai_task_auto_run_state
            SET next_check_at = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE),
                last_checked_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = 'oldest-task'
              AND enabled = TRUE
              AND next_check_at <= CURRENT_TIMESTAMP(6)
            """)
    int claimDueCheck();
}
