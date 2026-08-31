package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

/** 自动推进候选查询必须保留稳定顺序、安全状态范围与服务端批量上限。 */
class TaskMapperSqlTest {

    @Test
    void oldestAutoRunCandidatesExpandStatusesAndUseTheRequestedLimit() {
        Configuration mybatis = new Configuration();
        mybatis.addMapper(TaskMapper.class);

        String sql = mybatis.getMappedStatement(TaskMapper.class.getName() + ".selectOldestAutoRunCandidates")
                .getBoundSql(Map.of("projectId", "p1", "taskStatuses", List.of("待办", "收集中"), "limit", 3))
                .getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("t.status IN ( ? , ? )")
                .contains("r.status IN ('queued', 'claimed', 'running')")
                .contains("ORDER BY t.created_at IS NULL, t.created_at, t.id")
                .endsWith("LIMIT ?");
    }

    @Test
    void latestTaskRunsIncludeActiveStatesAndMergedTaskLinks() {
        Configuration mybatis = new Configuration();
        mybatis.addMapper(AiTaskRunMapper.class);

        String sql = mybatis.getMappedStatement(AiTaskRunMapper.class.getName() + ".selectLatestRunsForTasks")
                .getBoundSql(Map.of("taskIds", List.of("t1", "t2")))
                .getSql().replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("rt.task_id IN ( ? , ? )")
                .contains("ai_task_run_task newer_rt")
                .contains("newer.created_at > r.created_at")
                .doesNotContain("r.status IN");
    }
}
