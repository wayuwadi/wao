package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.common.VaultCryptoService;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 9 上验证 V78 Webhook 搬运与 V79 任务创建时间回填。 */
@Testcontainers(disabledWithoutDocker = true)
class FeishuWebhookMigrationMySqlTest {

    private static final String KEY = "ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=";
    private static final String URL = "https://open.feishu.cn/open-apis/bot/v2/hook/migration-secret-9012";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.0")
            .withDatabaseName("wao_webhook_migration_test")
            .withUsername("wao_test")
            .withPassword("wao_test");

    @Test
    void v78MovesCiphertextAndV79BackfillsReliableTaskCreationTimes() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target("77").load().migrate();
        VaultCryptoService crypto = new VaultCryptoService(KEY);
        VaultCryptoService.Encrypted encrypted = crypto.encrypt(URL);
        String stored = "enc:" + Base64.getEncoder().encodeToString(encrypted.nonce())
                + ":" + Base64.getEncoder().encodeToString(encrypted.ciphertext());
        String malformed = "enc:%%%%:%%%%";
        String oversized = "enc:" + Base64.getEncoder().encodeToString(new byte[12])
                + ":" + Base64.getEncoder().encodeToString(new byte[2049]);

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     UPDATE system_setting
                        SET cfg_value = CASE cfg_key
                            WHEN 'feishu.webhook.url' THEN ?
                            WHEN 'feishu.webhook.enabled' THEN 'true'
                            END,
                            updated_at = CURRENT_TIMESTAMP
                      WHERE cfg_key IN ('feishu.webhook.url', 'feishu.webhook.enabled')
                     """)) {
            statement.setString(1, malformed);
            assertThat(statement.executeUpdate()).isEqualTo(2);
        }

        migrateTo("78");
        assertThat(webhookRegistryCount()).as("非法 Base64 旧值必须跳过").isZero();

        rerunV78WithLegacy(oversized);
        assertThat(webhookRegistryCount()).as("超过 VARBINARY(2048) 的旧密文必须跳过").isZero();

        rerunV78WithLegacy(stored);

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT name, webhook_ciphertext, webhook_nonce, enabled, default_marker
                       FROM feishu_webhook_config
                     """);
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("name")).isEqualTo("系统通知（原配置）");
            byte[] ciphertext = result.getBytes("webhook_ciphertext");
            byte[] nonce = result.getBytes("webhook_nonce");
            assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain("migration-secret");
            assertThat(crypto.decrypt(ciphertext, nonce)).isEqualTo(URL);
            assertThat(result.getBoolean("enabled")).isTrue();
            assertThat(result.getString("default_marker")).isEqualTo("SYSTEM");
            assertThat(result.next()).isFalse();
        }

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO task (id, title, project_id, status, pr, owner_id, detail_json)
                     VALUES
                       ('t1787188786462', '服务端毫秒任务', 'p1', '待办', 'P2', 'wg', '{}'),
                       ('t1787188787000a', '前端幂等任务', 'p1', '待办', 'P2', 'wg', '{}'),
                       ('legacy-v79', '无可靠创建时间任务', 'p1', '待办', 'P2', 'wg', '{}')
                     """)) {
            statement.executeUpdate();
        }

        migrateTo("79");

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT id, ROUND(UNIX_TIMESTAMP(created_at) * 1000) AS created_millis
                       FROM task
                      WHERE id IN ('t1787188786462', 't1787188787000a', 'legacy-v79')
                      ORDER BY id
                     """);
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("legacy-v79");
            assertThat(result.getObject("created_millis")).isNull();

            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("t1787188786462");
            assertThat(result.getLong("created_millis")).isEqualTo(1787188786462L);

            assertThat(result.next()).isTrue();
            assertThat(result.getString("id")).isEqualTo("t1787188787000a");
            assertThat(result.getLong("created_millis")).isEqualTo(1787188787000L);
            assertThat(result.next()).isFalse();
        }

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     INSERT INTO task (id, title, project_id, status, pr, owner_id, detail_json)
                     VALUES ('post-v79', '迁移后新任务', 'p1', '待办', 'P2', 'wg', '{}')
                     """)) {
            statement.executeUpdate();
        }
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement(
                     "SELECT created_at FROM task WHERE id = 'post-v79'");
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getTimestamp("created_at")).isNotNull();
        }
    }

    /**
     * 同一临时 MySQL 中回放尚未发布的 V78，使损坏、超长和正常三种旧值都走真实 SQL。
     * 每次只撤掉本测试刚执行的 V78 与其新表，不会触碰其它 migration。
     */
    private void rerunV78WithLegacy(String stored) throws Exception {
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var drop = connection.prepareStatement("DROP TABLE feishu_webhook_config");
             var forget = connection.prepareStatement(
                     "DELETE FROM flyway_schema_history WHERE version = '78'");
             var update = connection.prepareStatement(
                     "UPDATE system_setting SET cfg_value = ?, updated_at = CURRENT_TIMESTAMP "
                             + "WHERE cfg_key = 'feishu.webhook.url'")) {
            drop.executeUpdate();
            forget.executeUpdate();
            update.setString(1, stored);
            update.executeUpdate();
        }
        migrateTo("78");
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(version).load().migrate();
    }

    private long webhookRegistryCount() throws Exception {
        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM feishu_webhook_config");
             var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }
}
