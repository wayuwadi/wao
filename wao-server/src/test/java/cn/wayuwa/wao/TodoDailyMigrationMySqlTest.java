package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the unchanged V175 SQL against real MySQL JSON semantics when Docker is available. */
@Testcontainers(disabledWithoutDocker = true)
class TodoDailyMigrationMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withConfigurationOverride("mysql-9-test-conf");

    @Test
    void baselineNormalizesExplicitJsonNullAndDoesNotInventOldWork() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE task (
                      id VARCHAR(64) PRIMARY KEY, title VARCHAR(256), project_id VARCHAR(64),
                      status VARCHAR(32), owner_id VARCHAR(64), start_date VARCHAR(16),
                      detail_json JSON, parent_task_id VARCHAR(64))
                    """);
            statement.execute("""
                    CREATE TABLE doc (
                      id VARCHAR(32) PRIMARY KEY, author_id VARCHAR(64), kb_id VARCHAR(64),
                      created_at DATETIME(6))
                    """);
            statement.execute("""
                    INSERT INTO task VALUES
                      ('t-null', '原任务', 'p1', '已完成', 'wg', '2026-10-03',
                       '{"parts":null,"steps":null,"subs":null,"startAt":null,"endAt":null,"schedulePeriod":null}', NULL),
                      ('t-parts', '协作任务', 'p1', '待办', 'wg', NULL,
                       '{"parts":["wg","wd"],"steps":[],"subs":[]}', NULL)
                    """);
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V175__todo_daily_fusion.sql"));
            for (String command : migration.split(";")) if (!command.isBlank()) statement.execute(command);
            try (var rs = statement.executeQuery("""
                    SELECT JSON_TYPE(JSON_EXTRACT(after_snapshot, '$.startAt')) AS start_type,
                           JSON_TYPE(JSON_EXTRACT(after_snapshot, '$.parts')) AS parts_type,
                           JSON_LENGTH(JSON_EXTRACT(after_snapshot, '$.parts')) AS parts_count,
                           event_type
                      FROM wao_todo_task_day_event WHERE member_id = 'wg' AND task_id = 't-null'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("start_type")).isEqualTo("NULL");
                assertThat(rs.getString("parts_type")).isEqualTo("ARRAY");
                assertThat(rs.getInt("parts_count")).isZero();
                assertThat(rs.getString("event_type")).isEqualTo("BASELINE");
                assertThat(rs.next()).isFalse();
            }
            try (var rs = statement.executeQuery("""
                    SELECT COUNT(*) FROM wao_todo_task_day_event
                     WHERE task_id = 't-parts' AND member_id = 'wd' AND event_type = 'BASELINE'
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (var rs = statement.executeQuery("SELECT COUNT(*) FROM wao_todo_task_day_entry")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }
}
