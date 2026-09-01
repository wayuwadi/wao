package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.wayuwa.wao.domain.ChatReviewRunEntity;
import cn.wayuwa.wao.domain.DocEntity;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.mapper.AiActivityMapper;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.ChatReviewRunMapper;
import cn.wayuwa.wao.mapper.DocMapper;
import cn.wayuwa.wao.service.AiAssistantService;
import cn.wayuwa.wao.service.ChatReviewAiQueue;
import cn.wayuwa.wao.service.FeishuChatArchiveVersionService;
import cn.wayuwa.wao.service.KnowledgeService;
import cn.wayuwa.wao.service.RunnerScriptService;
import cn.wayuwa.wao.service.StaleChatArchiveException;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL 9 上验证 V77、多节点抢占、归档 CAS 与 AI 事务回滚。 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.target=76",
        "spring.task.scheduling.enabled=false",
        "wao.chat-review.processing-enabled=false",
        "wao.ai.read-only.cleanup-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class ChatReviewMySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withConfigurationOverride("mysql-9-test-conf")
            .withDatabaseName("wao_chat_review_test")
            .withUsername("wao_test")
            .withPassword("wao_test");

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private KnowledgeService knowledge;
    @Autowired private DocMapper docs;
    @Autowired private AiActivityMapper activities;
    @Autowired private ChatReviewRunMapper reviews;
    @Autowired private AiAssistantRunMapper aiRuns;
    @Autowired private ChatReviewAiQueue aiQueue;
    @Autowired private AiAssistantService assistant;
    @Autowired private RunnerScriptService runnerScripts;
    @Autowired private FeishuChatArchiveVersionService archiveVersions;

    @Test
    void v77AndCrossNodeContractsHoldOnMySqlNine() throws Exception {
        seedLegacyArchiveBeforeV77();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("77").load().migrate();

        assertLegacyArchiveBackfilledWithoutChangingItsId();
        migrateLatest();
        assertGlobalActiveRunAndLeaseAreDatabaseSerialized();
        assertLeaseTakeoverFencesTheOldWorker();
        assertArchiveIdentityVersionAndSoftDeleteAreStable();
        assertScopedPatchCannotOverwriteANewerSync();
        assertArchiveSequenceIsUniqueAcrossConnections();
        assertAiInsertRollsBackWhenLeaseLinkIsLost();
        assertSecureCompletionAndDatabaseTimeoutAreSerialized();
        assertDatabaseClockExpiresAndClearsReadOnlyPrompts();
        assertUnifiedAiActivityCollations();
    }

    private void seedLegacyArchiveBeforeV77() {
        jdbc.update("INSERT INTO kb (id, name, icon, color, auto, perm, ord) "
                + "VALUES ('kb-chat-test', '聊天归档', 'ri-chat-1-line', '#4358D6', 1, '共享', 99)");
        jdbc.update("INSERT INTO kb_folder (id, kb_id, name, ord) "
                + "VALUES ('folder-chat-test', 'kb-chat-test', '2026 年 8 月', 1)");
        jdbc.update("""
                INSERT INTO doc
                    (id, kb_id, folder_id, title, author_id, auto, blocks_json, links_json, ord,
                     created_at, updated_at, rev, cascade_del, deleted_at, deleted_by)
                VALUES
                    ('doc-aaa-deleted-chat', 'kb-chat-test', 'folder-chat-test',
                     '飞书群聊 · 2026-08-18', 'auto', 1, JSON_ARRAY(), JSON_ARRAY(), 0,
                     CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 0,
                     CURRENT_TIMESTAMP(6), 'member-test')
                """);
        jdbc.update("""
                INSERT INTO doc
                    (id, kb_id, folder_id, title, author_id, auto, blocks_json, links_json, ord,
                     created_at, updated_at, rev, cascade_del)
                VALUES
                    ('doc-legacy-chat', 'kb-chat-test', 'folder-chat-test',
                     '飞书群聊 · 2026-08-18', 'auto', 1, JSON_ARRAY(), JSON_ARRAY(), 1,
                     CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 0)
                """);
        jdbc.update("""
                INSERT INTO doc
                    (id, kb_id, folder_id, title, author_id, auto, blocks_json, links_json, ord,
                     created_at, updated_at, rev, cascade_del, deleted_at, deleted_by)
                VALUES
                    ('doc-all-deleted-a', 'kb-chat-test', 'folder-chat-test',
                     '飞书群聊 · 2026-08-17', 'auto', 1, JSON_ARRAY(), JSON_ARRAY(), 2,
                     CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 0,
                     CURRENT_TIMESTAMP(6), 'member-test'),
                    ('doc-all-deleted-z', 'kb-chat-test', 'folder-chat-test',
                     '飞书群聊 · 2026-08-17', 'auto', 1, JSON_ARRAY(), JSON_ARRAY(), 3,
                     CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 1, 0,
                     CURRENT_TIMESTAMP(6), 'member-test')
                """);
        upsertSetting("feishu.chat-sync.chat-id", "oc-legacy-chat");
        upsertSetting("feishu.chat-sync.kb-id", "kb-chat-test");
    }

    private void upsertSetting(String key, String value) {
        jdbc.update("""
                INSERT INTO system_setting (cfg_key, cfg_value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE cfg_value = VALUES(cfg_value), updated_at = VALUES(updated_at)
                """, key, value);
    }

    private void assertLegacyArchiveBackfilledWithoutChangingItsId() {
        assertThat(jdbc.queryForMap("""
                SELECT auto_sync_scope, auto_sync_date, auto_sync_version
                  FROM doc WHERE id = 'doc-legacy-chat'
                """))
                .containsEntry("auto_sync_scope", "oc-legacy-chat")
                .containsEntry("auto_sync_version", 0L);
        assertThat(jdbc.queryForMap("""
                SELECT auto_sync_scope, auto_sync_date, auto_sync_version
                  FROM doc WHERE id = 'doc-aaa-deleted-chat'
                """))
                .containsEntry("auto_sync_scope", null)
                .containsEntry("auto_sync_date", null)
                .containsEntry("auto_sync_version", null);
        assertThat(jdbc.queryForMap("""
                SELECT auto_sync_scope, auto_sync_date, auto_sync_version
                  FROM doc WHERE id = 'doc-all-deleted-a'
                """))
                .containsEntry("auto_sync_scope", "oc-legacy-chat")
                .containsEntry("auto_sync_version", 0L);
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM doc WHERE id = 'doc-all-deleted-a'",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT auto_sync_scope, auto_sync_date, auto_sync_version
                  FROM doc WHERE id = 'doc-all-deleted-z'
                """))
                .containsEntry("auto_sync_scope", null)
                .containsEntry("auto_sync_date", null)
                .containsEntry("auto_sync_version", null);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'chat_review_run'",
                Integer.class)).isEqualTo(32);
    }

    private void assertGlobalActiveRunAndLeaseAreDatabaseSerialized() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Boolean>> inserts = List.of(
                    () -> insertActiveAfter(start, "cr-mysql-a", "oc-a"),
                    () -> insertActiveAfter(start, "cr-mysql-b", "oc-b"));
            List<Future<Boolean>> results = new ArrayList<>();
            for (Callable<Boolean> insert : inserts) results.add(pool.submit(insert));
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) if (result.get()) succeeded++;
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        String activeId = jdbc.queryForObject(
                "SELECT id FROM chat_review_run WHERE stage = 'WAITING'", String.class);
        CountDownLatch claimStart = new CountDownLatch(1);
        var claimPool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = claimPool.submit(() -> claimAfter(claimStart, activeId, "worker-a"));
            Future<Integer> second = claimPool.submit(() -> claimAfter(claimStart, activeId, "worker-b"));
            claimStart.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
        } finally {
            claimPool.shutdownNow();
        }
        jdbc.update("UPDATE chat_review_run SET stage = 'FAILED', worker_token = NULL, lease_until = NULL "
                + "WHERE id = ?", activeId);
    }

    private void assertLeaseTakeoverFencesTheOldWorker() {
        String runId = "cr-lease-takeover";
        String oldWorker = "worker-old";
        String newWorker = "worker-new";
        insertRun(runId, "oc-lease", "VERIFYING");
        assertThat(reviews.claimLease(runId, oldWorker)).isEqualTo(1);
        jdbc.update("UPDATE chat_review_run SET lease_until = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) "
                + "WHERE id = ?", runId);
        assertThat(reviews.claimLease(runId, newWorker)).isEqualTo(1);

        int staleStageWrite = reviews.update(null, new LambdaUpdateWrapper<ChatReviewRunEntity>()
                .set(ChatReviewRunEntity::getStage, "ANALYZING")
                .set(ChatReviewRunEntity::getProgressCurrent, 99)
                .setSql("lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 MINUTE)")
                .eq(ChatReviewRunEntity::getId, runId)
                .eq(ChatReviewRunEntity::getWorkerToken, oldWorker)
                .apply("lease_until > CURRENT_TIMESTAMP(6)")
                .in(ChatReviewRunEntity::getStage,
                        List.of("WAITING", "SYNCING", "VERIFYING", "ANALYZING")));
        assertThat(staleStageWrite).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT stage, progress_current, worker_token
                  FROM chat_review_run WHERE id = ?
                """, runId))
                .containsEntry("stage", "VERIFYING")
                .containsEntry("progress_current", 0)
                .containsEntry("worker_token", newWorker);

        int aiRunCount = jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class);
        assertThatThrownBy(() -> aiQueue.enqueue(runId, oldWorker, "只返回 JSON"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("执行权已转移");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class))
                .isEqualTo(aiRunCount);
        assertThat(jdbc.queryForObject(
                "SELECT ai_run_id IS NULL FROM chat_review_run WHERE id = ?", Boolean.class, runId))
                .isTrue();
        jdbc.update("UPDATE chat_review_run SET stage = 'FAILED', worker_token = NULL, lease_until = NULL "
                + "WHERE id = ?", runId);
    }

    private boolean insertActiveAfter(CountDownLatch start, String id, String chatId) throws InterruptedException {
        start.await();
        try {
            jdbc.update("""
                    INSERT INTO chat_review_run
                        (id, chat_id, chat_name, kb_id,
                         speaker_one_id, speaker_one_name, speaker_two_id, speaker_two_name,
                         start_date, end_date, stage, progress_total,
                         created_by, created_by_name, created_at, updated_at)
                    VALUES (?, ?, '测试群', 'kb-chat-test',
                            'ou-one', '娃哥', 'ou-two', '娃弟',
                            '2026-08-18', '2026-08-18', 'WAITING', 1,
                            'member-test', '测试成员', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                    """, id, chatId);
            return true;
        } catch (DataAccessException expectedConflict) {
            return false;
        }
    }

    private int claimAfter(CountDownLatch start, String id, String worker) throws InterruptedException {
        start.await();
        return reviews.claimLease(id, worker);
    }

    private void assertArchiveIdentityVersionAndSoftDeleteAreStable() {
        LocalDate day = LocalDate.of(2026, 8, 19);
        List<DocBlockDto> blocks = List.of(new DocBlockDto(null, null, "版本 10", null, null));
        DocDto chatA = knowledge.upsertAutoDoc(
                "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-19",
                blocks, "oc-Ab", day, 10L);
        DocDto chatB = knowledge.upsertAutoDoc(
                "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-19",
                blocks, "oc-aB", day, 11L);
        assertThat(chatA.id()).isNotEqualTo(chatB.id());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM doc
                 WHERE kb_id = 'kb-chat-test' AND auto_sync_date = '2026-08-19'
                """, Integer.class)).isEqualTo(2);

        assertThatThrownBy(() -> knowledge.upsertAutoDoc(
                "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-19",
                List.of(new DocBlockDto(null, null, "旧版本 9", null, null)),
                "oc-Ab", day, 9L))
                .isInstanceOf(StaleChatArchiveException.class);

        DocEntity staleOrdinaryEdit = docs.selectById(chatA.id());
        staleOrdinaryEdit.setAutoSyncScope("oc-evil");
        staleOrdinaryEdit.setAutoSyncDate(day.minusDays(1));
        staleOrdinaryEdit.setAutoSyncVersion(1L);
        staleOrdinaryEdit.setTitle("普通编辑不能回退栅栏");
        docs.updateById(staleOrdinaryEdit);
        assertThat(docs.selectById(chatA.id()))
                .extracting(DocEntity::getAutoSyncScope, DocEntity::getAutoSyncDate,
                        DocEntity::getAutoSyncVersion)
                .containsExactly("oc-Ab", day, 10L);

        DocEntity staleMetadataSnapshot = docs.selectById(chatA.id());
        knowledge.upsertAutoDoc(
                "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-19",
                List.of(new DocBlockDto(null, null, "版本 11", null, null)),
                "oc-Ab", day, 11L);
        staleMetadataSnapshot.setFolderId("folder-chat-test");
        staleMetadataSnapshot.setOrd(7);
        assertThat(docs.updateTreeOrders("kb-chat-test", List.of(staleMetadataSnapshot))).isEqualTo(1);
        docs.markDeleted(staleMetadataSnapshot.getId(), java.time.LocalDateTime.now(), "member-test", false);
        docs.clearDeleted(staleMetadataSnapshot.getId());
        assertThat(jdbc.queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$[0].p')) AS body,
                       auto_sync_version, ord, deleted_at
                  FROM doc WHERE id = ?
                """, chatA.id()))
                .containsEntry("body", "版本 11")
                .containsEntry("auto_sync_version", 11L)
                .containsEntry("ord", 7)
                .containsEntry("deleted_at", null);

        jdbc.update("UPDATE doc SET deleted_at = CURRENT_TIMESTAMP(6), deleted_by = 'member-test' "
                + "WHERE id = ?", chatA.id());
        DocDto restored = knowledge.upsertAutoDoc(
                "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-19",
                List.of(new DocBlockDto(null, null, "版本 12", null, null)),
                "oc-Ab", day, 12L);
        assertThat(restored.id()).isEqualTo(chatA.id());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM doc WHERE id = ?",
                Boolean.class, chatA.id())).isTrue();
    }

    private void assertScopedPatchCannotOverwriteANewerSync() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 20);
        DocDto archive = knowledge.upsertAutoDoc(
                "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-20",
                List.of(new DocBlockDto(null, null, "同步版本 100", null, null)),
                "oc-patch-cas", day, 100L);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch newerSyncSaved = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> stalePatch = pool.submit(() -> {
                DocEntity snapshot = docs.selectById(archive.id());
                snapshotRead.countDown();
                newerSyncSaved.await();
                return docs.updateAutoArchiveManually(snapshot.getId(), snapshot.getFolderId(),
                        snapshot.getTitle(), "[{\"p\":\"旧 PATCH 正文\"}]", snapshot.getLinksJson(),
                        snapshot.getRev() + 1, snapshot.getAutoSyncScope(), snapshot.getAutoSyncDate(),
                        snapshot.getAutoSyncVersion(), 102L);
            });
            snapshotRead.await();
            knowledge.upsertAutoDoc(
                    "kb-chat-test", "folder-chat-test", "飞书群聊 · 2026-08-20",
                    List.of(new DocBlockDto(null, null, "并发新同步 101", null, null)),
                    "oc-patch-cas", day, 101L);
            newerSyncSaved.countDown();
            assertThat(stalePatch.get()).isZero();
        } finally {
            newerSyncSaved.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(blocks_json, '$[0].p')) AS body,
                       auto_sync_version
                  FROM doc WHERE id = ?
                """, archive.id()))
                .containsEntry("body", "并发新同步 101")
                .containsEntry("auto_sync_version", 101L);
    }

    private void assertArchiveSequenceIsUniqueAcrossConnections() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                results.add(pool.submit(archiveVersions::nextVersion));
            }
            Set<Long> versions = new HashSet<>();
            for (Future<Long> result : results) versions.add(result.get());
            assertThat(versions).hasSize(8);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertAiInsertRollsBackWhenLeaseLinkIsLost() {
        jdbc.update("""
                INSERT INTO ai_runner_node
                    (id, name, platform, engines, token_hash, script_hash, last_seen_at, created_at)
                VALUES ('runner-mysql', 'MySQL 验收 Runner', 'linux', 'codex',
                        REPEAT('a', 64), ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, runnerScripts.hash());
        insertRun("cr-ai-rollback", "oc-ai", "VERIFYING");
        jdbc.update("UPDATE chat_review_run SET worker_token = 'current-worker', "
                + "lease_until = DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 MINUTE) "
                + "WHERE id = 'cr-ai-rollback'");

        assertThatThrownBy(() -> aiQueue.enqueue("cr-ai-rollback", "stale-worker", "只返回 JSON"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("执行权已转移");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_assistant_run WHERE member_id = 'internal-cr-ai-rollback'",
                Integer.class)).isZero();

        String aiRunId = aiQueue.enqueue("cr-ai-rollback", "current-worker", "只返回 JSON");
        assertThat(aiRunId).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT stage FROM chat_review_run WHERE id = 'cr-ai-rollback'", String.class))
                .isEqualTo("ANALYZING");
    }

    private void assertSecureCompletionAndDatabaseTimeoutAreSerialized() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 16; attempt++) {
                String runId = "ac-secure-race-" + attempt;
                String claimToken = "claim-secure-race-" + attempt;
                jdbc.update("""
                        INSERT INTO ai_assistant_run
                            (id, member_id, runner_id, status, prompt_text, claim_token,
                             created_at, claimed_at, updated_at)
                        VALUES
                            (?, 'internal-secure-race', 'runner-mysql', 'secure_running',
                             '完整聊天材料', ?, CURRENT_TIMESTAMP(6),
                             DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 31 MINUTE), CURRENT_TIMESTAMP(6))
                        """, runId, claimToken);
                assertThat(aiRuns.findExpiredReadOnlyRunningIds(30 * 60)).contains(runId);
                String timeoutUpdateKey = jdbc.queryForObject("""
                        EXPLAIN FORMAT=TRADITIONAL UPDATE ai_assistant_run
                           SET status = 'failed'
                         WHERE id = ?
                           AND status = 'secure_running'
                           AND member_id LIKE 'internal-%'
                           AND claimed_at <= DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1800 SECOND)
                        """, (result, row) -> result.getString("key"), runId);
                assertThat(timeoutUpdateKey).isEqualTo("PRIMARY");

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> completed = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return aiRuns.completeSucceeded(runId, "runner-mysql",
                            claimToken, "secure_running", "完整报告");
                });
                Future<Integer> timedOut = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return assistant.expireReadOnlyJobs();
                });
                ready.await();
                start.countDown();
                int completionRows = completed.get();
                int timeoutRows = timedOut.get();
                assertThat(completionRows + timeoutRows).isEqualTo(1);

                Map<String, Object> terminal = jdbc.queryForMap("""
                        SELECT status, prompt_text, claim_token, response_text, finished_at
                          FROM ai_assistant_run WHERE id = ?
                        """, runId);
                assertThat(terminal)
                        .containsEntry("prompt_text", null)
                        .containsEntry("claim_token", null);
                assertThat(terminal.get("finished_at")).isNotNull();
                if (completionRows == 1) {
                    assertThat(terminal)
                            .containsEntry("status", "succeeded")
                            .containsEntry("response_text", "完整报告");
                } else {
                    assertThat(terminal)
                            .containsEntry("status", "failed")
                            .containsEntry("response_text", null);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void insertRun(String id, String chatId, String stage) {
        jdbc.update("""
                INSERT INTO chat_review_run
                    (id, chat_id, chat_name, kb_id,
                     speaker_one_id, speaker_one_name, speaker_two_id, speaker_two_name,
                     start_date, end_date, stage, progress_total,
                     created_by, created_by_name, created_at, updated_at)
                VALUES (?, ?, '测试群', 'kb-chat-test',
                        'ou-one', '娃哥', 'ou-two', '娃弟',
                        '2026-08-18', '2026-08-18', ?, 1,
                        'member-test', '测试成员', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, id, chatId, stage);
    }

    private void assertDatabaseClockExpiresAndClearsReadOnlyPrompts() {
        jdbc.update("""
                INSERT INTO ai_assistant_run
                    (id, member_id, status, prompt_text, created_at, updated_at)
                VALUES ('ac-expired-mysql', 'internal-expired-mysql', 'secure_queued', '完整聊天材料',
                        DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 61 MINUTE), CURRENT_TIMESTAMP(6))
                """);
        assertThat(assistant.expireReadOnlyJobs()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT status, prompt_text, claim_token
                  FROM ai_assistant_run WHERE id = 'ac-expired-mysql'
                """))
                .containsEntry("status", "failed")
                .containsEntry("prompt_text", null)
                .containsEntry("claim_token", null);
    }

    private void migrateLatest() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    private void assertUnifiedAiActivityCollations() {
        jdbc.update("""
                INSERT INTO ai_account (id, provider, label, credential_json)
                VALUES ('activity-collation-account', 'deepseek', 'DeepSeek 验收账号', '{}')
                """);
        jdbc.update("""
                UPDATE chat_review_run
                   SET ai_capability_id = 'activity-collation-account'
                 WHERE id = 'cr-lease-takeover'
                """);

        assertThat(activities.page("CHAT_REVIEW", "ALL", 0, 50))
                .filteredOn(row -> "cr-lease-takeover".equals(row.getId()))
                .singleElement()
                .satisfies(row -> assertThat(row.getCapabilityLabel()).isEqualTo("DeepSeek 验收账号"));
        assertThat(activities.count("ALL", "ALL")).isGreaterThanOrEqualTo(1);
    }
}
