package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 验证退役迁移只清理独立记忆，并保留项目工程记忆与个人决策。 */
class RetireUnusedFeaturesMigrationTest {
    @Test
    void migrationKeepsProjectMemoryAndDecisions() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:retire-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE memory_document (id VARCHAR(64) PRIMARY KEY, scope_type VARCHAR(24), category VARCHAR(32))");
        jdbc.execute("CREATE TABLE memory_outbox (id VARCHAR(64) PRIMARY KEY, aggregate_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE kb (id VARCHAR(32) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE kb_folder (id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE doc (id VARCHAR(32) PRIMARY KEY, kb_id VARCHAR(32))");
        jdbc.execute("CREATE TABLE ai_runner_node (id VARCHAR(32) PRIMARY KEY, memory_collection_enabled BOOLEAN,"
                + " last_memory_collection_attempt_at TIMESTAMP, last_memory_collected_at TIMESTAMP,"
                + " last_memory_collection_error VARCHAR(1000))");
        jdbc.execute("CREATE TABLE system_setting (cfg_key VARCHAR(128) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE account_identity (id VARCHAR(36) PRIMARY KEY, provider VARCHAR(16))");
        jdbc.execute("CREATE TABLE account_login_event (id VARCHAR(36) PRIMARY KEY, provider VARCHAR(16))");
        jdbc.update("INSERT INTO memory_document VALUES ('project','project','convention'),"
                + "('decision','personal','decision'),('personal','personal','context'),"
                + "('machine','machine','context')");
        jdbc.update("INSERT INTO memory_outbox VALUES ('keep','project'),('remove','personal')");
        jdbc.update("INSERT INTO kb VALUES ('kb-memory'),('normal')");
        jdbc.update("INSERT INTO kb_folder VALUES ('old','kb-memory')");
        jdbc.update("INSERT INTO doc VALUES ('old','kb-memory'),('normal','normal')");
        jdbc.update("INSERT INTO system_setting VALUES ('auth.wechat-official.token'),('other')");
        jdbc.update("INSERT INTO account_identity VALUES ('wechat','wechat'),('email','email'),('feishu','feishu')");
        jdbc.update("INSERT INTO account_login_event VALUES ('wechat-login','wechat'),('feishu-login','feishu')");

        Path migration = Path.of("src/main/resources/db/migration/V172__retire_unused_standalone_features.sql");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(migration));
        }

        assertThat(jdbc.queryForList("SELECT id FROM memory_document ORDER BY id", String.class))
                .containsExactly("decision", "project");
        assertThat(jdbc.queryForList("SELECT id FROM memory_outbox", String.class)).containsExactly("keep");
        assertThat(jdbc.queryForList("SELECT id FROM kb", String.class)).containsExactly("normal");
        assertThat(jdbc.queryForList("SELECT id FROM doc", String.class)).containsExactly("normal");
        assertThat(jdbc.queryForList("SELECT cfg_key FROM system_setting", String.class)).containsExactly("other");
        assertThat(jdbc.queryForList("SELECT id FROM account_identity ORDER BY id", String.class))
                .containsExactly("email", "feishu");
        assertThat(jdbc.queryForList("SELECT id FROM account_login_event", String.class))
                .containsExactly("feishu-login");
    }
}
