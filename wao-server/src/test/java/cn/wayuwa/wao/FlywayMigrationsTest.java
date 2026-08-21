package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Flyway migration 文件的静态约束。
 *
 * <p>存在的理由：两个分支各自新建了 {@code V18__*.sql}，文件名不同所以 git 静默合并，
 * 但 Flyway 启动时会因为「同一个版本号有多个 migration」直接失败 —— 应用起不来、
 * 健康检查不过、整次部署回滚。这个测试把那次事故变成本地就能发现的编译期约束。
 */
class FlywayMigrationsTest {

    /**
     * 读源码目录而不是 {@code target/classes}：Maven 不会清掉已删除的资源副本，
     * 改过文件名却没 clean 时，编译输出里会同时留着新旧两份，把这个测试变成假警报。
     */
    private List<Path> migrations() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        if (!Files.isDirectory(dir)) {
            dir = Path.of("wao-server/src/main/resources/db/migration");
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql")).sorted().toList();
        }
    }

    @Test
    void everyMigrationHasItsOwnVersionNumber() throws Exception {
        Map<String, List<String>> byVersion = migrations().stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.groupingBy(name -> name.substring(1, name.indexOf("__"))));

        List<String> duplicated = byVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "V" + entry.getKey() + " → " + entry.getValue())
                .toList();

        assertThat(duplicated)
                .as("同一版本号有多个 migration，Flyway 会直接启动失败，必须重新编号")
                .isEmpty();
    }

    @Test
    void migrationsFollowTheVersionedNamingConvention() throws Exception {
        assertThat(migrations().stream().map(path -> path.getFileName().toString()))
                .allMatch(name -> name.matches("V\\d+__[A-Za-z0-9_]+\\.sql"), "V<数字>__<名字>.sql");
    }

    @Test
    void chatReviewMigrationKeepsDatabaseEnforcedSingleActiveRunAndLeaseColumns() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V77__chat_review.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("worker_token VARCHAR(64)", "lease_until DATETIME(6)")
                .contains("ALTER TABLE ai_assistant_run", "claim_token VARCHAR(64) NULL")
                .contains("processing_date DATE NULL")
                .contains("active_chat_id VARCHAR(128) GENERATED ALWAYS AS")
                .contains("stage IN ('WAITING', 'SYNCING', 'VERIFYING', 'ANALYZING')")
                .contains("UNIQUE KEY uk_chat_review_active_chat (active_chat_id)")
                .doesNotContain("current_date DATE");
    }

    @Test
    void webhookRegistryMigrationMovesOnlyEncryptedLegacyValuesAndEnforcesOneDefault() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V78__feishu_webhook_configs.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("webhook_ciphertext VARBINARY(2048)")
                .contains("UNIQUE KEY uk_feishu_webhook_default (default_marker)")
                .contains("url_setting.cfg_value LIKE 'enc:%:%'")
                .contains("FROM_BASE64(SUBSTRING_INDEX(url_setting.cfg_value, ':', -1))")
                .contains("decoded.webhook_nonce IS NOT NULL")
                .contains("OCTET_LENGTH(decoded.webhook_nonce) = 12")
                .contains("decoded.webhook_ciphertext IS NOT NULL")
                .contains("OCTET_LENGTH(decoded.webhook_ciphertext) BETWEEN 16 AND 2048")
                .doesNotContain("open-apis/bot/v2/hook/");
    }

    @Test
    void taskCreatedAtMigrationBackfillsBothServerAndIdempotentClientIds() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V79__task_created_at.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("created_at DATETIME(3) NULL DEFAULT CURRENT_TIMESTAMP(3)")
                .contains("^t[0-9]{13}[0-9a-z]*$")
                .contains("SUBSTRING(id, 2, 13)")
                .contains("SET created_at = NULL");
    }

    @Test
    void desktopCompanionMigrationStoresOnlyCredentialHashesAndScopedReceipts() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V81__desktop_companion.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("poll_secret_hash CHAR(64)", "token_hash CHAR(64)")
                .contains("token_hash CHAR(64)", "UNIQUE KEY uk_companion_device_token")
                .contains("UNIQUE KEY uk_companion_receipt (device_id, activity_id)")
                .doesNotContain("poll_secret VARCHAR", "device_token VARCHAR");
    }
}
