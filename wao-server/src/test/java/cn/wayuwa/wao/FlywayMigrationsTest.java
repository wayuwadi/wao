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
    void sharedChatMigrationKeepsIdempotencyAndIndependentDeliveryState() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V96__shared_chat.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE shared_chat_message")
                .contains("UNIQUE KEY uk_shared_chat_member_token (member_id, client_token)")
                .contains("feishu_status  VARCHAR(16)   NOT NULL")
                .contains("CONSTRAINT fk_shared_chat_member")
                .contains("CONSTRAINT fk_shared_chat_image");
    }

    @Test
    void sharedChatBridgeMigrationKeepsExternalIdempotencyReadAndReactionFacts() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V98__shared_chat_feishu_bridge.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("uk_shared_chat_feishu_message (feishu_message_id)")
                .contains("MODIFY COLUMN content MEDIUMTEXT NOT NULL")
                .contains("CREATE TABLE shared_chat_message_read")
                .contains("uk_shared_chat_read (message_id, reader_key, source)")
                .contains("CREATE TABLE shared_chat_reaction")
                .contains("uk_shared_chat_reaction (message_id, reactor_key, emoji_type)")
                .contains("FOREIGN KEY (reply_to_id)");
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
    void integratedChatReviewMigrationKeepsRealMessageSnapshotAndArtifactLinks() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V100__shared_chat_review_integration.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("source_kind VARCHAR(24) NOT NULL DEFAULT 'FEISHU_ARCHIVE'")
                .contains("source_message_ids_json JSON NULL")
                .contains("report_url VARCHAR(1024) NULL", "report_doc_id VARCHAR(32) NULL")
                .contains("FOREIGN KEY (report_doc_id)")
                .contains("REFERENCES doc(id) ON DELETE SET NULL");
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
    void configurableSystemReportBackfillsAggregationSnapshot() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V101__configurable_system_periodic_report.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("ADD COLUMN aggregation_options_json JSON NULL")
                .contains("JSON_ARRAY('TASK_CREATED', 'TASK_COMPLETED', 'AI_RUN')")
                .contains("MODIFY COLUMN aggregation_options_json JSON NOT NULL");
    }

    @Test
    void aiRunDeliveryStageBackfillsOnlyVerifiedHistoricalOutcomes() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V109__ai_run_delivery_stage.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("ADD COLUMN delivery_stage VARCHAR(32) NOT NULL DEFAULT 'coding'")
                .contains("WHEN status = 'succeeded' THEN 'deployment_succeeded'")
                .contains("WHEN status = 'failed' THEN 'code_failed'")
                .doesNotContain("WHEN status = 'failed' THEN 'deployment_failed'");
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
    void mockAutomationCleanupOnlyTargetsKnownSeedRows() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V107__remove_mock_automation_jobs.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("DELETE FROM automation_run")
                .contains("DELETE FROM automation_job")
                .contains("WHERE job_id IN (")
                .contains("id IN ('j1', 'j2', 'j3', 'j4')")
                .contains("handler IS NULL")
                .doesNotContain("TRUNCATE", "DELETE FROM automation_job;");
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

    @Test
    void httpInspectionTopTenMigrationOnlyUpgradesThePreviousDefaults() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V116__http_inspection_top_ten.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("'http.inspection.top-n', '10'")
                .contains("IF(cfg_value = '5'")
                .contains("'http.inspection.max-endpoints', '10'")
                .contains("JOIN system_setting AS top_setting")
                .contains("max_setting.cfg_value = '2'")
                .contains("top_setting.cfg_value = '10'")
                .doesNotContain("DELETE FROM system_setting", "UPDATE system_setting SET");
    }

    @Test
    void aiInspectionHistoryMigrationBackfillsOnlyTheFixedRealJob() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V118__ai_inspection_activity_history.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("activity_type VARCHAR(32) NOT NULL DEFAULT 'FEATURE_DEVELOPMENT'")
                .contains("ai_run_id VARCHAR(32) NULL")
                .contains("WHERE job_id = 'job-http-performance-ai-run'")
                .contains("result LIKE '成功 · 已创建 AI Run %'")
                .contains("SET run.activity_type = 'AI_INSPECTION'")
                .doesNotContain("UPDATE ai_task_run SET", "UPDATE automation_run SET ai_run_id = NULL");
    }

    @Test
    void hostedPageSourceMigrationOnlyReclassifiesStableAiRunReportIds() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V120__hosted_page_source_type.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL_UPLOAD'")
                .contains("SET source_type = 'AI_RUN_REPORT'")
                .contains("WHERE id LIKE 'run-report-%'")
                .doesNotContain("DELETE FROM hosted_page", "TRUNCATE");
    }

    @Test
    void quotaSnapshotHistoryStoresOnlySanitizedRealPercentages() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V121__ai_quota_snapshot_history.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE ai_quota_snapshot")
                .contains("remaining_percent TINYINT", "observed_at       DATETIME")
                .contains("100 - quota_percent")
                .contains("quota_status = 'SYNCED'", "quota_percent IS NOT NULL")
                .doesNotContain("credential_json", "quota_raw_json");
    }

    @Test
    void configurableTaskAutoRunMigrationDefaultsToWaoTodoAndKeepsWebhookOptional() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V97__configurable_task_auto_run.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("ADD COLUMN project_id VARCHAR(32) NULL")
                .contains("task_statuses_json JSON NOT NULL DEFAULT (JSON_ARRAY('待办'))")
                .contains("notification_webhook_id VARCHAR(32) NULL")
                .contains("WHERE p.name = 'Wa All in One'");
    }

    @Test
    void batchTaskAutoRunMigrationBackfillsTheSelectedProjectConcurrency() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString().equals("V110__batch_oldest_task_auto_run.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("batch_size INT NOT NULL DEFAULT 2")
                .contains("CHECK (batch_size BETWEEN 1 AND 10)")
                .contains("JOIN ai_project_config c ON c.project_id = s.project_id")
                .contains("LEAST(GREATEST(c.max_parallel_runs, 1), 10)")
                .contains("WHERE s.id = 'oldest-task'");
    }

    @Test
    void lunarCalendarReminderMigrationKeepsExistingRowsCompatible() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V103__lunar_calendar_group_reminders.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("calendar_type VARCHAR(16) NOT NULL DEFAULT 'SOLAR'")
                .contains("notification_type VARCHAR(16) NOT NULL DEFAULT 'DIRECT'")
                .contains("lunar_leap_month TINYINT(1) NOT NULL DEFAULT 0")
                .contains("chat_id VARCHAR(128) NULL")
                .doesNotContain("DROP TABLE", "RENAME TABLE");
    }

    @Test
    void notificationCenterLoginEventsKeepOnlyMinimalSuccessfulLoginFacts() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V111__notification_center_login_events.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE account_login_event")
                .contains("idx_login_event_member_time (member_id, logged_in_at)")
                .contains("idx_login_event_time (logged_in_at)")
                .contains("ON DELETE CASCADE")
                .doesNotContainIgnoringCase("ip_address", "user_agent", "email", "provider_subject", "password");
    }

    @Test
    void feishuReactionDispatchKeepsEventAndRunIdempotencyInMysql() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V112__feishu_reaction_ai_run_trigger.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("CREATE TABLE feishu_quick_collect_ai_run_trigger")
                .contains("PRIMARY KEY (event_key)")
                .contains("REFERENCES task (id) ON DELETE CASCADE")
                .contains("REFERENCES ai_task_run (id) ON DELETE SET NULL");
    }

    @Test
    void dailyReminderMigrationSeedsAllRequestedTimesOnTheRealHandler() throws Exception {
        Path migration = migrations().stream()
                .filter(path -> path.getFileName().toString()
                        .equals("V123__daily_notification_reminders.sql"))
                .findFirst().orElseThrow();
        String sql = Files.readString(migration);

        assertThat(sql)
                .contains("'0 50 8 * * *'", "'0 0 12 * * *'", "'0 30 12 * * *'", "'0 0 18 * * *'")
                .contains("'feishu-notification'")
                .contains("'通知设置 · 每日提醒'")
                .doesNotContain("INSERT INTO automation_run");
    }
}
