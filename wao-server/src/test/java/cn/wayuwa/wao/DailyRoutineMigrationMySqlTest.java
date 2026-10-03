package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Validates the unchanged V176 SQL against MySQL's JSON, FK and uniqueness semantics. */
@Testcontainers(disabledWithoutDocker = true)
class DailyRoutineMigrationMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withConfigurationOverride("mysql-9-test-conf");

    @Test
    void migrationCreatesVersionedRulesWithoutOrdinaryTaskTables() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V176__todo_daily_routines.sql"));
            for (String command : migration.split(";")) if (!command.isBlank()) statement.execute(command);
            statement.execute("""
                    INSERT INTO wao_todo_routine_template VALUES
                      ('11111111-1111-4111-8111-111111111111','m1',1,NOW(6),NOW(6))
                    """);
            statement.execute("""
                    INSERT INTO wao_todo_routine_revision VALUES
                      ('22222222-2222-4222-8222-222222222222',
                       '11111111-1111-4111-8111-111111111111','m1',
                       '2026-10-03',NULL,'晨间回顾','MORNING','[1,2,3,4,5,6,7]',0,1,0,NOW(6))
                    """);
            statement.execute("""
                    INSERT INTO wao_todo_routine_occurrence VALUES
                      ('11111111-1111-4111-8111-111111111111','m1','2026-10-03',
                       '22222222-2222-4222-8222-222222222222','晨间回顾',
                       'MORNING',0,'DONE',1,NOW(6))
                    """);
            try (var rs = statement.executeQuery("""
                    SELECT JSON_LENGTH(weekdays_json) FROM wao_todo_routine_revision
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(7);
            }
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO wao_todo_routine_occurrence VALUES
                      ('11111111-1111-4111-8111-111111111111','m1','2026-10-03',
                       '22222222-2222-4222-8222-222222222222','晨间回顾',
                       'MORNING',0,'SKIPPED',1,NOW(6))
                    """)).isInstanceOf(SQLException.class);
            try (var rs = statement.executeQuery("SHOW TABLES LIKE 'task'")) {
                assertThat(rs.next()).isFalse();
            }
        }
    }
}
