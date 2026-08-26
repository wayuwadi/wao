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
    void workdayRhythmDashboardKeepsHistoricalSnapshotsAndChannelLevelIdempotency() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V84__workday_rhythm_daily_dashboard.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE workday_rhythm_setting")
                .contains("CREATE TABLE workday_rhythm_checklist_item")
                .contains("CREATE TABLE workday_rhythm_checklist_occurrence")
                .contains("UNIQUE KEY uk_rhythm_occurrence_item_date (item_id, occurrence_date)")
                .contains("UNIQUE KEY uk_rhythm_day_document (document_id)")
                .contains("UNIQUE KEY uk_rhythm_delivery_occurrence_channel (occurrence_id, channel)")
                .contains("UNIQUE KEY uk_rhythm_item_member_system (member_id, system_key)");
    }

    @Test
    void feishuPersonalQuickCaptureUsesDatabaseLevelEventIdempotency() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V85__feishu_personal_quick_capture.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE feishu_quick_collect_event")
                .contains("event_key       VARCHAR(191) NOT NULL")
                .contains("PRIMARY KEY (event_key)")
                .contains("sender_open_id", "capture_kind", "target_id", "error_message");
    }

    @Test
    void systemPeriodicReportKeepsOneSnapshotPerNaturalPeriod() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V88__system_periodic_report.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE system_periodic_report")
                .contains("UNIQUE KEY uk_system_report_period (period_type, period_key)")
                .contains("task_sources_json", "run_sources_json", "ai_run_metered_count", "total_tokens")
                .contains("idx_task_created_at", "idx_task_completed_at", "idx_ai_run_created_at");
    }

    @Test
    void mergedTaskRunMigrationBackfillsHistoryAndKeepsStableOrder() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V91__merged_task_ai_run.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE ai_task_run_task")
                .contains("PRIMARY KEY (run_id, task_id)")
                .contains("UNIQUE KEY uk_ai_task_run_task_order (run_id, ord)")
                .contains("INSERT INTO ai_task_run_task (run_id, task_id, ord)")
                .contains("SELECT id, task_id, 0", "FROM ai_task_run");
    }

    @Test
    void oldestTaskAutoRunMigrationDefaultsToEnabledTwoHourPolicy() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V92__oldest_task_auto_run.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE ai_task_auto_run_state")
                .contains("enabled         BOOLEAN      NOT NULL DEFAULT TRUE")
                .contains("idle_hours      INT          NOT NULL DEFAULT 2")
                .contains("CHECK (idle_hours BETWEEN 1 AND 168)")
                .contains("VALUES ('oldest-task', TRUE, 2)");
    }

    @Test
    void networkOutageMigrationMovesHttpInspectionAfterRecovery() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V94__network_outage_window.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("schedule_label = '每日 06:30'")
                .contains("cron_expr = '0 30 6 * * *'")
                .contains("CURTIME() < '06:30:00'")
                .contains("WHERE id = 'job-http-performance-ai-run'");
    }
}
