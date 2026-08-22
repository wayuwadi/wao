package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.ProjectMemoryCandidate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;

/** 使用真实 H2 表模拟 Task A 写入、全新 Task B 召回和跨项目隔离。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:projectmemoryloop;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql({"/project-memory-schema.sql", "/unified-memory-schema.sql"})
class ProjectMemoryLoopIntegrationTest {
    @Autowired private ProjectMemoryService memories;
    @Autowired private UnifiedMemoryService unified;
    @MockBean private ProjectService projects;

    @Test
    void successfulTaskAFeedsFreshTaskBButNeverAnotherProject() {
        AiTaskRunEntity taskARun = run("ar-task-a", "task-a", "p-wao");
        var written = memories.persistCandidates(taskARun, List.of(new ProjectMemoryCandidate(
                "pitfall", "Flyway migration 编号冲突",
                "新增 migration 前核对 origin/main，并运行 FlywayMigrationsTest；部署健康检查已通过。",
                List.of("https://github.com/acme/wao/actions/runs/100"))));
        assertThat(written).hasSize(1);

        var candidate = unified.get("wg", written.get(0).id(), null);
        assertThat(candidate.revision().state()).isEqualTo("draft");
        assertThat(memories.writeReferences(List.of("ar-task-a")).get("ar-task-a"))
                .extracting(item -> item.id()).containsExactly(candidate.id());

        TaskEntity taskB = new TaskEntity();
        taskB.setTitle("新增 Flyway migration");
        taskB.setDescription("避免 migration 编号冲突并跑测试");
        assertThat(memories.recall(run("ar-before-review", "task-before", "p-wao"), taskB)).isEmpty();
        unified.publish("wg", candidate.revision().id());
        var recalled = memories.recall(run("ar-task-b", "task-b", "p-wao"), taskB);

        assertThat(recalled).extracting(item -> item.title()).containsExactly("Flyway migration 编号冲突");
        assertThat(memories.readReferences(List.of("ar-task-b")).get("ar-task-b"))
                .extracting(item -> item.id()).containsExactly(written.get(0).id());

        var otherProject = memories.recall(run("ar-other", "task-other", "p-private"), taskB);
        assertThat(otherProject).isEmpty();
    }

    private static AiTaskRunEntity run(String id, String taskId, String projectId) {
        AiTaskRunEntity run = new AiTaskRunEntity();
        run.setId(id);
        run.setTaskId(taskId);
        run.setProjectId(projectId);
        return run;
    }
}
