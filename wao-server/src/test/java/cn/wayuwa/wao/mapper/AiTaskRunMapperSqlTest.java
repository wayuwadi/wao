package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 合并任务关系使用注解动态 SQL；启动前先证明 foreach 和关联查询能被 MyBatis 正确解析。 */
class AiTaskRunMapperSqlTest {

    private static Configuration mybatis;

    @BeforeAll
    static void parseMapperAnnotations() {
        mybatis = new Configuration();
        mybatis.addMapper(AiTaskRunMapper.class);
    }

    @Test
    void taskLinksExpandRunIdsAndTaskArchiveUsesRelationTable() {
        String links = sql("selectTaskLinks", Map.of("runIds", List.of("ar1", "ar2")));
        String archive = sql("selectTaskRuns", Map.of("taskId", "t2", "limit", 30));
        String active = sql("countActiveMainRunsForTask", Map.of("taskId", "t2"));

        assertThat(links).contains("WHERE run_id IN ( ? , ? )").contains("ORDER BY run_id, ord");
        assertThat(archive).contains("JOIN ai_task_run_task rt ON rt.run_id = r.id")
                .contains("WHERE rt.task_id = ?", "LIMIT ?");
        assertThat(active).contains("r.sub_id IS NULL")
                .contains("r.status IN ('queued', 'claimed', 'running')");
    }

    @Test
    void claimUsesQueuedStateAndPendingInputAsDatabaseCompareAndSetConditions() {
        String claim = sql("claimQueuedRun", Map.of(
                "runId", "ar1", "runnerId", "rn1", "pendingInput", "继续执行",
                "progress", "正在启动", "claimedAt", java.time.LocalDateTime.now()));

        assertThat(claim).contains("SET status = 'running'", "pending_input = NULL")
                .contains("WHERE id = ? AND status = 'queued'")
                .contains("pending_input = ?", "pending_input IS NULL AND ? IS NULL");
    }

    private static String sql(String method, Map<String, Object> parameters) {
        return mybatis.getMappedStatement(AiTaskRunMapper.class.getName() + "." + method)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }
}
