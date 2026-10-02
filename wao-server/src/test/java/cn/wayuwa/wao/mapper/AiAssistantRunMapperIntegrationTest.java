package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 用真实 SQL CAS 验证同一 Runner 重领时旧尝试不能完成新尝试。 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:assistantclaimtoken;DB_CLOSE_DELAY=-1;MODE=MySQL")
class AiAssistantRunMapperIntegrationTest {

    @Autowired private AiAssistantRunMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void recreateTable() {
        jdbc.execute("DROP TABLE IF EXISTS ai_assistant_run");
        jdbc.execute("""
                CREATE TABLE ai_assistant_run (
                    id VARCHAR(32) PRIMARY KEY,
                    member_id VARCHAR(64) NULL,
                    ai_capability_id VARCHAR(32) NULL,
                    requested_model VARCHAR(64) NULL,
                    usage_purpose VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
                    web_search_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    runner_id VARCHAR(32) NULL,
                    claim_token VARCHAR(64) NULL,
                    status VARCHAR(32) NOT NULL,
                    prompt_text CLOB NULL,
                    reference_image_ids VARCHAR(512) NULL,
                    web_context CLOB NULL,
                    response_text CLOB NULL,
                    error_text CLOB NULL,
                    created_at TIMESTAMP(6) NULL,
                    claimed_at TIMESTAMP(6) NULL,
                    finished_at TIMESTAMP(6) NULL,
                    updated_at TIMESTAMP(6) NOT NULL
                )
                """);
    }

    @Test
    void previewRequiresCurrentClaimAndCannotOverwriteTerminalResult() {
        var run = readOnlyRun("preview", "provider_queued");
        run.setUsagePurpose("POLISH");
        mapper.insertReadOnlyQueued(run);
        assertThat(mapper.claimProviderQueued("preview", "current")).isEqualTo(1);
        assertThat(mapper.updateProviderPreview("preview", "stale", "错误")).isZero();
        assertThat(mapper.updateProviderPreview("preview", "current", "一部分")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_text FROM ai_assistant_run WHERE id = 'preview'", String.class)).isEqualTo("一部分");
        assertThat(mapper.completeProviderSucceeded("preview", "current", "完整结果")).isEqualTo(1);
        assertThat(mapper.updateProviderPreview("preview", "current", "迟到")).isZero();
        assertThat(jdbc.queryForObject("SELECT response_text FROM ai_assistant_run WHERE id = 'preview'", String.class)).isEqualTo("完整结果");
    }

    @Test
    void insertReadOnlyQueuedPreservesTheRequestedQueueKindAndUsesDatabaseTime() {
        AiAssistantRunEntity ordinary = readOnlyRun("ordinary", "queued");
        AiAssistantRunEntity durable = readOnlyRun("durable", "secure_queued");
        ordinary.setUsagePurpose("BACKGROUND");
        durable.setUsagePurpose("POLISH");

        assertThat(mapper.insertReadOnlyQueued(ordinary)).isEqualTo(1);
        assertThat(mapper.insertReadOnlyQueued(durable)).isEqualTo(1);

        assertThat(rowWithCreatedAt("ordinary"))
                .containsEntry("STATUS", "queued")
                .containsEntry("USAGE_PURPOSE", "BACKGROUND")
                .containsEntry("MEMBER_ID", "internal-test")
                .containsEntry("PROMPT_TEXT", "请总结")
                .doesNotContainValue(null);
        assertThat(rowWithCreatedAt("durable"))
                .containsEntry("STATUS", "secure_queued")
                .containsEntry("USAGE_PURPOSE", "POLISH")
                .containsEntry("MEMBER_ID", "internal-test")
                .containsEntry("PROMPT_TEXT", "请总结")
                .doesNotContainValue(null);
    }

    @Test
    void staleAttemptCannotCompleteAReclaimByTheSameRunner() {
        LocalDateTime firstClaim = LocalDateTime.now();
        insert("ac1", firstClaim.minusMinutes(1));

        assertThat(mapper.claimQueued(
                "ac1", "rn1", "attempt-old", "queued", "running")).isEqualTo(1);
        assertThat(mapper.requeueStale("ac1", firstClaim.plusMinutes(1))).isEqualTo(1);
        assertThat(row("ac1"))
                .containsEntry("STATUS", "queued")
                .containsEntry("CLAIM_TOKEN", null);

        LocalDateTime secondClaim = firstClaim.plusMinutes(4);
        assertThat(mapper.claimQueued(
                "ac1", "rn1", "attempt-new", "queued", "running")).isEqualTo(1);
        assertThat(mapper.completeSucceeded(
                "ac1", "rn1", "attempt-old", "running", "迟到答复")).isZero();
        assertThat(row("ac1"))
                .containsEntry("STATUS", "running")
                .containsEntry("CLAIM_TOKEN", "attempt-new")
                .containsEntry("RESPONSE_TEXT", null);

        assertThat(mapper.completeSucceeded(
                "ac1", "rn1", "attempt-new", "running", "新答复")).isEqualTo(1);
        assertThat(row("ac1"))
                .containsEntry("STATUS", "succeeded")
                .containsEntry("CLAIM_TOKEN", null)
                .containsEntry("RESPONSE_TEXT", "新答复");
    }

    @Test
    void everyTerminalPathClearsTheAttemptToken() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 19, 10, 0);
        insert("failed-by-runner", now);
        assertThat(mapper.claimQueued(
                "failed-by-runner", "rn1", "attempt-failed", "queued", "running"))
                .isEqualTo(1);
        assertThat(mapper.completeFailed(
                "failed-by-runner", "rn1", "attempt-failed", "running", "Codex 失败"))
                .isEqualTo(1);
        assertThat(row("failed-by-runner"))
                .containsEntry("STATUS", "failed")
                .containsEntry("CLAIM_TOKEN", null);

        insert("failed-by-timeout", now);
        assertThat(mapper.claimQueued(
                "failed-by-timeout", "rn1", "attempt-timeout", "queued", "running"))
                .isEqualTo(1);
        assertThat(mapper.failActive("failed-by-timeout", "执行超时")).isEqualTo(1);
        assertThat(row("failed-by-timeout"))
                .containsEntry("STATUS", "failed")
                .containsEntry("CLAIM_TOKEN", null);
    }

    @Test
    void secureQueuedRunRequiresTheSecureClaimAndCompletionStates() {
        insert("secure-ac1", "secure_queued", LocalDateTime.now());

        assertThat(mapper.claimQueued(
                "secure-ac1", "rn1", "secure-attempt", "queued", "running")).isZero();
        assertThat(row("secure-ac1"))
                .containsEntry("STATUS", "secure_queued")
                .containsEntry("CLAIM_TOKEN", null);

        assertThat(mapper.claimQueued(
                "secure-ac1", "rn1", "secure-attempt", "secure_queued", "secure_running"))
                .isEqualTo(1);
        assertThat(mapper.completeSucceeded(
                "secure-ac1", "rn1", "secure-attempt", "running", "旧协议结果"))
                .isZero();
        assertThat(row("secure-ac1"))
                .containsEntry("STATUS", "secure_running")
                .containsEntry("CLAIM_TOKEN", "secure-attempt")
                .containsEntry("RESPONSE_TEXT", null);

        assertThat(mapper.completeSucceeded(
                "secure-ac1", "rn1", "secure-attempt", "secure_running", "安全协议结果"))
                .isEqualTo(1);
        assertThat(row("secure-ac1"))
                .containsEntry("STATUS", "succeeded")
                .containsEntry("CLAIM_TOKEN", null)
                .containsEntry("RESPONSE_TEXT", "安全协议结果");
    }

    @Test
    void providerWebSearchMaterialIsHeldOnlyBetweenTheTwoStages() {
        insert("web-ac1", "web_search_queued", LocalDateTime.now());

        assertThat(mapper.claimQueued("web-ac1", "rn1", "search-attempt",
                "web_search_queued", "web_search_running")).isEqualTo(1);
        assertThat(mapper.completeWebSearchSucceeded("web-ac1", "rn1", "search-attempt",
                "来源：https://example.com/current")).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT status, runner_id, claim_token, web_context
                  FROM ai_assistant_run WHERE id = 'web-ac1'
                """))
                .containsEntry("STATUS", "provider_queued")
                .containsEntry("RUNNER_ID", null)
                .containsEntry("CLAIM_TOKEN", null)
                .containsEntry("WEB_CONTEXT", "来源：https://example.com/current");

        assertThat(mapper.claimProviderQueued("web-ac1", "provider-attempt")).isEqualTo(1);
        assertThat(mapper.completeProviderSucceeded(
                "web-ac1", "provider-attempt", "最终答复")).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT status, prompt_text, web_context, response_text
                  FROM ai_assistant_run WHERE id = 'web-ac1'
                """))
                .containsEntry("STATUS", "succeeded")
                .containsEntry("PROMPT_TEXT", null)
                .containsEntry("WEB_CONTEXT", null)
                .containsEntry("RESPONSE_TEXT", "最终答复");
    }

    private void insert(String id, LocalDateTime updatedAt) {
        insert(id, "queued", updatedAt);
    }

    @Test
    void providerNoteClaimIsExclusiveAndDuplicateCallbacksCannotChangeSuccess() {
        insert("note-once", "provider_queued", LocalDateTime.now());
        assertThat(mapper.claimProviderQueued("note-once", "first")).isEqualTo(1);
        assertThat(mapper.claimProviderQueued("note-once", "duplicate")).isZero();
        assertThat(mapper.completeProviderSucceeded("note-once", "first", "完整正文")).isEqualTo(1);
        assertThat(mapper.completeProviderSucceeded("note-once", "first", "重复回调")).isZero();
        assertThat(mapper.completeProviderFailed("note-once", "first", "迟到失败")).isZero();
        assertThat(mapper.updateProviderPreview("note-once", "first", "迟到预览")).isZero();
        assertThat(mapper.failActive("note-once", "迟到超时")).isZero();
        assertThat(row("note-once")).containsEntry("STATUS", "succeeded").containsEntry("RESPONSE_TEXT", "完整正文");
    }

    @Test
    void providerFailureClearsPartialOutputAndCannotBeRevivedByOldWorker() {
        insert("note-failed", "provider_queued", LocalDateTime.now());
        mapper.claimProviderQueued("note-failed", "old-worker");
        assertThat(mapper.updateProviderPreview("note-failed", "old-worker", "半篇")).isEqualTo(1);
        assertThat(mapper.completeProviderFailed("note-failed", "old-worker", "连接中断，请重试")).isEqualTo(1);
        assertThat(mapper.completeProviderSucceeded("note-failed", "old-worker", "迟到结果")).isZero();
        assertThat(mapper.claimProviderQueued("note-failed", "new-worker")).isZero();
        assertThat(jdbc.queryForMap("SELECT status, prompt_text, response_text, claim_token FROM ai_assistant_run WHERE id = 'note-failed'"))
                .containsEntry("STATUS", "failed").containsEntry("PROMPT_TEXT", null)
                .containsEntry("RESPONSE_TEXT", null).containsEntry("CLAIM_TOKEN", null);
    }

    private void insert(String id, String status, LocalDateTime updatedAt) {
        jdbc.update("""
                INSERT INTO ai_assistant_run (id, status, prompt_text, updated_at)
                VALUES (?, ?, '请总结', ?)
                """, id, status, updatedAt);
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("""
                SELECT status, claim_token, response_text FROM ai_assistant_run WHERE id = ?
                """, id);
    }

    private Map<String, Object> rowWithCreatedAt(String id) {
        return jdbc.queryForMap("""
                SELECT status, member_id, usage_purpose, prompt_text, created_at, updated_at
                  FROM ai_assistant_run WHERE id = ?
                """, id);
    }

    private AiAssistantRunEntity readOnlyRun(String id, String status) {
        AiAssistantRunEntity run = new AiAssistantRunEntity();
        run.setId(id);
        run.setMemberId("internal-test");
        run.setWebSearchEnabled(false);
        run.setStatus(status);
        run.setPromptText("请总结");
        return run;
    }
}
