package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import cn.wayuwa.wao.mapper.WorkItemNumberMapper;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Execute the actual migration and MyBatis statements on MySQL 9. */
@Testcontainers(disabledWithoutDocker = true)
class WorkItemNumberMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
        .withConfigurationOverride("mysql-9-test-conf");

    @Test void backfillsLegacyIdsAndPreservesNumbersDuringIdempotentAllocation() throws Exception {
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var st = c.createStatement()) {
            st.execute("CREATE TABLE task(id VARCHAR(64) PRIMARY KEY, detail_json JSON)");
            st.execute("""
                INSERT INTO task VALUES ('t1', '{"subs":[{"text":"legacy"},{"id":"s2"},{"id":"s2"}]}'), ('t2', NULL)
                """);
            String sql = Files.readString(Path.of("src/main/resources/db/migration/V154__work_item_numbers.sql"));
            for (String command : sql.split(";")) if (!command.isBlank()) st.execute(command);
        }
        var source = new UnpooledDataSource(MYSQL.getDriverClassName(), MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(WorkItemNumberMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(WorkItemNumberMapper.class);
            var first = mapper.find(List.of("t1/", "t1/t1#0", "t1/s2", "t2/"));
            assertThat(first).hasSize(4);
            assertThat(first.stream().filter(e -> e.itemKey().equals("t1/")).findFirst().orElseThrow().number()).isZero();
            mapper.allocate(List.of("t1/", "t1/new"));
            assertThat(mapper.find(List.of("t1/", "t1/t1#0", "t1/s2", "t2/"))).containsExactlyInAnyOrderElementsOf(first);
            assertThat(mapper.find(List.of("t1/new")).get(0).number()).isGreaterThan(3L);
        }
    }
}
