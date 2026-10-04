package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;

/** 真实事务和行锁覆盖双节点竞争、重启恢复、手动并发与失败边界。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tianrendaily;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=10000")
@Sql(scripts = {"/tianren-share-schema.sql", "/db/migration/V54__tianren_daily_reading.sql", "/db/migration/V62__tianren_reading_share.sql"})
@Sql(statements = {
    "CREATE TABLE IF NOT EXISTS member(id VARCHAR(32) PRIMARY KEY)",
    "CREATE TABLE IF NOT EXISTS tianren_visual(interpretation_run_id VARCHAR(48) PRIMARY KEY)",
    "MERGE INTO member KEY(id) VALUES ('wg'),('other')",
    "DROP TABLE IF EXISTS member_preference",
    "CREATE TABLE member_preference(member_id VARCHAR(32),pref_key VARCHAR(128),pref_value CLOB,updated_at TIMESTAMP, PRIMARY KEY(member_id,pref_key))",
    "DROP TABLE IF EXISTS ai_assistant_run",
    "CREATE TABLE ai_assistant_run(id VARCHAR(32) PRIMARY KEY,member_id VARCHAR(32),conversation_id VARCHAR(32),user_message_id VARCHAR(32),ai_capability_id VARCHAR(64),requested_model VARCHAR(64),reasoning_effort VARCHAR(16),usage_purpose VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',web_search_enabled BOOLEAN,status VARCHAR(32),runner_id VARCHAR(32),claim_token VARCHAR(64),prompt_text CLOB,reference_image_ids VARCHAR(512),web_context CLOB,response_text CLOB,error_text CLOB,created_at TIMESTAMP,updated_at TIMESTAMP,claimed_at TIMESTAMP,finished_at TIMESTAMP)"
})
class TianrenDailyAutomationServiceTest {
    @Autowired TianrenReadingService readings;
    @Autowired TianrenDailyReadingMapper readingMapper;
    @Autowired MemberPreferenceMapper preferences;
    @Autowired AiAssistantRunMapper runs;
    @Autowired AiAssistantConversationService conversations;
    @Autowired LocalCodexConversationCompletion localCompletion;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockBean AiAssistantService assistant;
    @MockBean WorkdayRhythmDashboardService rhythm;
    private TianrenDailyAutomationService service;

    @BeforeEach void setup() {
        service = at(LocalTime.of(9, 0));
        config("wg", true, true, true, "oc-own");
        config("other", false, true, true, "oc-other");
        queueNormally();
        when(assistant.tianrenStatus()).thenReturn(new cn.wayuwa.wao.dto.AssistantStatusDto(true, "本机 Codex 已就绪", "local-notes-test", "本机 Codex"));
    }

    private TianrenDailyAutomationService at(LocalTime time) {
        Clock clock = Clock.fixed(LocalDate.now(TianrenDailyAutomationService.ZONE).atTime(time)
                .atZone(TianrenDailyAutomationService.ZONE).toInstant(), TianrenDailyAutomationService.ZONE);
        return new TianrenDailyAutomationService(readings, readingMapper, preferences, runs, conversations,
                assistant, rhythm, json, manager, clock);
    }

    private void config(String member, boolean automatic, boolean enabled, boolean notify, String target) {
        preferences.upsert(member, WorkdayRhythmNotificationService.CONFIG_KEY,
                "{\"energyNotification\":{\"enabled\":" + enabled + ",\"notifyTianren\":" + notify
                + ",\"autoTianrenAtNine\":" + automatic + ",\"channel\":\"CHAT\",\"chatId\":\"oc-legacy\","
                + "\"tianrenTarget\":{\"channel\":\"CHAT\",\"chatId\":\"" + target + "\"}}}");
    }

    private void queueNormally() {
        when(assistant.enqueueTianren(any(), anyString(), anyString())).thenAnswer(call -> {
            AssistantChatRequest request = call.getArgument(0);
            String member = call.getArgument(1);
            AiAssistantRunEntity run = new AiAssistantRunEntity();
            run.setId("ac" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            run.setMemberId(member); run.setUsagePurpose("TIANREN"); run.setStatus("queued"); run.setPromptText(call.getArgument(2));
            run.setCreatedAt(LocalDateTime.now()); run.setUpdatedAt(LocalDateTime.now());
            conversations.enqueue(run, request.conversationId(), member, request.message());
            return run.getId();
        });
    }

    @Test void startsOnlyAtNineAndOnlyForExplicitlyEnabledMember() {
        at(LocalTime.of(8, 59, 59)).runDue();
        assertThat(readings.today("wg")).isNull();
        service.runDue();
        assertThat(readings.today("wg")).isNotNull();
        assertThat(readings.today("other")).isNull();
        verify(assistant).enqueueTianren(any(), eq("wg"), contains("authoritative_tianren_cast"));
    }

    @Test void localQueuedRunsBlockDuplicatesAndStatusIsMemberScoped() {
        service.runDue();
        db.update("UPDATE ai_assistant_run SET status='secure_queued'");
        service.runDue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.enqueue("wg", "再次点击", false)).hasMessageContaining("正在解读");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class)).isEqualTo(1);
        assertThat(service.status("wg").message()).contains("排队中");
        assertThat(service.status("other").runId()).isNull();
    }

    @Test void localCompletionCommitsPrivateReplyOnceAndRollsBackTogether() {
        service.runDue();
        String id = db.queryForObject("SELECT id FROM ai_assistant_run", String.class);
        db.update("UPDATE ai_assistant_run SET status='provider_running',claim_token='claim'");
        var run = runs.selectById(id);
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(localCompletion.complete(run, "claim", "合成测试解读")).isEqualTo(1);
            throw new IllegalStateException("隔离事务故障");
        })).hasMessageContaining("隔离事务故障");
        assertThat(runs.selectById(id).getStatus()).isEqualTo("provider_running");
        assertThat(conversations.latestAssistantReply(run.getConversationId(), "wg")).isNull();
        assertThat(localCompletion.complete(run, "claim", "合成测试解读")).isEqualTo(1);
        assertThat(localCompletion.complete(run, "claim", "迟到重复结果")).isZero();
        assertThat(conversations.latestAssistantReply(run.getConversationId(), "wg")).isEqualTo("合成测试解读");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_message WHERE role='assistant'", Integer.class)).isEqualTo(1);
    }

    @Test void respectsMasterSwitchNotificationSwitchAndExplicitEmptyTarget() {
        config("wg", true, false, true, "oc-own"); service.runDue();
        config("wg", true, true, false, "oc-own"); service.runDue();
        config("wg", true, true, true, ""); service.runDue();
        assertThat(readings.today("wg")).isNull();
        verifyNoInteractions(assistant);
    }

    @Test void twoNodesAndRepeatedRecoveryKeepOneCastConversationAndRun() throws Exception {
        var node2 = at(LocalTime.of(9, 1));
        CompletableFuture.allOf(CompletableFuture.runAsync(service::runDue),
                CompletableFuture.runAsync(node2::runDue)).get(15, TimeUnit.SECONDS);
        service.runDue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM tianren_daily_reading", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_conversation", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class)).isEqualTo(1);
        verify(rhythm, times(1)).completeSystemItem(eq("wg"), any(), eq("TIANREN"));
    }

    @Test void manualCastIsReusedAndManualInterpretationSharesAutomaticLock() throws Exception {
        var original = readings.createToday("wg");
        CompletableFuture.allOf(CompletableFuture.runAsync(service::runDue), CompletableFuture.runAsync(() -> {
            try { service.enqueue("wg", "今天的关注", false); }
            catch (cn.wayuwa.wao.common.BadRequestException expected) { assertThat(expected).hasMessageContaining("正在解读"); }
        })).get(15, TimeUnit.SECONDS);
        assertThat(readings.today("wg").id()).isEqualTo(original.id());
        assertThat(readings.today("wg").lines()).isEqualTo(original.lines());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class)).isEqualTo(1);
    }

    @Test void offlineKeepsCastAndLaterRecoversWithoutOrphanConversations() {
        doThrow(new cn.wayuwa.wao.common.BadRequestException("离线")).when(assistant).enqueueTianren(any(), anyString(), anyString());
        service.runDue();
        var saved = readings.today("wg");
        assertThat(saved).isNotNull();
        assertThat(saved.conversationId()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_conversation", Integer.class)).isZero();
        reset(assistant); queueNormally(); service.runDue();
        assertThat(readings.today("wg").id()).isEqualTo(saved.id());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class)).isEqualTo(1);
    }

    @Test void failedRunsWaitFiveMinutesAndStopAfterThreeAttempts() {
        service.runDue();
        db.update("UPDATE ai_assistant_run SET status='failed'");
        service.runDue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class)).isEqualTo(1);
        for (int i = 0; i < 3; i++) {
            db.update("UPDATE ai_assistant_run SET status='failed',updated_at=?", LocalDateTime.now().minusMinutes(6));
            service.runDue();
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_assistant_run", Integer.class)).isEqualTo(3);
    }

    @Test void savedInterpretationIsNeverRegeneratedByAutomaticRecovery() {
        service.runDue();
        var row = runs.selectList(null).get(0);
        db.update("UPDATE ai_assistant_run SET status='succeeded'");
        conversations.appendAssistantReply(row, "已保存的今日解读");
        clearInvocations(assistant);
        service.runDue();
        verifyNoInteractions(assistant);
    }
}
