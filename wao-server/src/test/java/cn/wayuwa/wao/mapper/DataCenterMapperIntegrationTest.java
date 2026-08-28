package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.service.AiAssistantService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:datacenter;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.flyway.enabled=false",
        "spring.sql.init.schema-locations=classpath:data-center-schema.sql",
        "spring.task.scheduling.enabled=false"
})
class DataCenterMapperIntegrationTest {

    @Autowired
    private DataCenterMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private AiAssistantService aiAssistantService;

    @Test
    void executesCompleteCatalogQueryAgainstMysqlCompatibleSchema() {
        jdbc.update("INSERT INTO project (id) VALUES ('p1')");
        jdbc.update("INSERT INTO task (id, created_at) VALUES ('t1', TIMESTAMP '2026-08-28 09:00:00')");
        jdbc.update("INSERT INTO ai_task_run (id, created_at, finished_at) VALUES "
                + "('r1', TIMESTAMP '2026-08-28 09:10:00', TIMESTAMP '2026-08-28 09:20:00')");
        jdbc.update("INSERT INTO doc (id, created_at, updated_at) VALUES "
                + "('d1', TIMESTAMP '2026-08-28 08:00:00', TIMESTAMP '2026-08-28 09:30:00')");
        jdbc.update("INSERT INTO memory_revision (id, created_at) VALUES "
                + "('m1', TIMESTAMP '2026-08-28 09:40:00')");
        jdbc.update("INSERT INTO shared_chat_message (id, created_at) VALUES "
                + "('c1', TIMESTAMP '2026-08-28 09:50:00')");
        jdbc.update("INSERT INTO http_request_metric (id, started_at) VALUES "
                + "(1, TIMESTAMP '2026-08-28 10:00:00')");
        jdbc.update("INSERT INTO ai_project_config (project_id, repo_url, updated_at) VALUES "
                + "('p1', 'https://github.com/example/project.git', TIMESTAMP '2026-08-28 10:10:00')");

        Map<String, DataCenterSnapshotRow> rows = mapper.snapshots().stream()
                .collect(Collectors.toMap(DataCenterSnapshotRow::getSourceId, Function.identity()));

        assertThat(rows).hasSize(8);
        assertThat(rows.values()).allSatisfy(row -> assertThat(row.getRecordCount()).isEqualTo(1L));
        assertThat(rows.get("projects").getLatestAt()).isNull();
        assertThat(rows.get("ai-runs").getLatestAt()).isEqualTo(LocalDateTime.of(2026, 8, 28, 9, 20));
        assertThat(rows.get("git-repositories").getLatestAt()).isEqualTo(LocalDateTime.of(2026, 8, 28, 10, 10));
    }
}
