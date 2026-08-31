package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectAiRunTriggerEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.domain.TaskEntity;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectAiRunTriggerMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import cn.wayuwa.wao.mapper.TaskMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class FeishuQuickCollectAiRunServiceTest {
    private final FeishuQuickCollectEventMapper collectEvents = mock(FeishuQuickCollectEventMapper.class);
    private final FeishuQuickCollectAiRunTriggerMapper triggers = mock(FeishuQuickCollectAiRunTriggerMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final AiRunService aiRuns = mock(AiRunService.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final FeishuQuickCollectReactionPolicy reactionPolicy = new FeishuQuickCollectReactionPolicy(settings);
    private final FeishuQuickCollectAiRunService service = new FeishuQuickCollectAiRunService(
            collectEvents, triggers, identities, accounts, tasks, aiRuns, settings, feishu, reactionPolicy);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void collectedTaskOwnedByTheBoundOperator() {
        FeishuQuickCollectEventEntity collected = new FeishuQuickCollectEventEntity();
        collected.setMessageId("om_source");
        collected.setTargetId("t1");
        collected.setStatus("CREATED");
        when(collectEvents.findCreatedByMessageId("om_source")).thenReturn(collected);
        AccountIdentityEntity identity = new AccountIdentityEntity();
        identity.setAccountId("a1");
        when(identities.findByProviderSubject("feishu", "ou_owner")).thenReturn(identity);
        when(identities.findByProviderUnionSubject("feishu", "on_owner")).thenReturn(identity);
        AccountEntity account = new AccountEntity();
        account.setId("a1");
        account.setMemberId("wd");
        when(accounts.selectById("a1")).thenReturn(account);
        TaskEntity task = new TaskEntity();
        task.setId("t1");
        task.setCreatedBy("wd");
        task.setOwnerId("wd");
        when(tasks.selectById("t1")).thenReturn(task);
    }

    @Test
    void ownerFlightReactionCreatesOnePlanRunAndPersistsTheRunLink() throws Exception {
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn("ar1");
        when(aiRuns.enqueue(eq(List.of("t1")), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(true), eq(false), eq("wd"), eq("https://wao.example"))).thenReturn(List.of(run));

        assertThat(service.trigger(event("ev1", "StatusInFlight", "ou_owner"),
                "https://wao.example")).isTrue();

        verify(aiRuns).enqueue(eq(List.of("t1")), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(true), eq(false), eq("wd"), eq("https://wao.example"));
        ArgumentCaptor<FeishuQuickCollectAiRunTriggerEntity> saved =
                ArgumentCaptor.forClass(FeishuQuickCollectAiRunTriggerEntity.class);
        verify(triggers).updateById(saved.capture());
        assertThat(saved.getValue().getEventKey()).isEqualTo("event:ev1");
        assertThat(saved.getValue().getAiRunId()).isEqualTo("ar1");
    }

    @Test
    void duplicateReactionEventNeverCreatesAnotherRun() throws Exception {
        when(triggers.insert(any(FeishuQuickCollectAiRunTriggerEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThat(service.trigger(event("ev1", "StatusInFlight", "ou_owner"),
                "https://wao.example")).isTrue();

        verify(aiRuns, never()).enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void otherMemberCannotDispatchTheCollectedTask() throws Exception {
        AccountIdentityEntity otherIdentity = new AccountIdentityEntity();
        otherIdentity.setAccountId("a2");
        when(identities.findByProviderSubject("feishu", "ou_other")).thenReturn(otherIdentity);
        AccountEntity other = new AccountEntity();
        other.setMemberId("wg");
        when(accounts.selectById("a2")).thenReturn(other);

        assertThat(service.trigger(event("ev2", "StatusInFlight", "ou_other"),
                "https://wao.example")).isTrue();

        verify(triggers, never()).insert(any(FeishuQuickCollectAiRunTriggerEntity.class));
        verify(aiRuns, never()).enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonFlightReactionStaysAvailableToTheSharedChatBridge() throws Exception {
        assertThat(service.trigger(event("ev3", "HEART", "ou_owner"), "https://wao.example")).isFalse();
        verify(collectEvents, never()).findCreatedByMessageId(any());
    }

    @Test
    void enqueueFailureRollsBackToCallerAndAddsAnErrorReaction() throws Exception {
        when(aiRuns.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("没有可用 Runner"));
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("cli", "secret")).thenReturn("token");

        assertThatThrownBy(() -> service.trigger(event("ev4", "StatusInFlight", "ou_owner"),
                "https://wao.example")).hasMessageContaining("Runner");
        verify(feishu).addMessageReaction("token", "om_source", "ERROR");
    }

    @Test
    void configuredTriggerReactionReplacesTheDefaultFlight() throws Exception {
        when(settings.get(SettingService.QUICK_COLLECT_TRIGGER_EMOJI,
                FeishuQuickCollectReactionPolicy.DEFAULT_TRIGGER_EMOJI)).thenReturn("CheckMark");
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn("ar2");
        when(aiRuns.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(run));

        assertThat(service.trigger(event("ev5", "StatusInFlight", "ou_owner"),
                "https://wao.example")).isFalse();
        assertThat(service.trigger(event("ev6", "CheckMark", "ou_owner"), "https://wao.example")).isTrue();
    }

    @Test
    void authenticatedPolledFlightUsesReactionIdForDurableIdempotency() {
        AiRunDto run = mock(AiRunDto.class);
        when(run.id()).thenReturn("ar-polled");
        when(aiRuns.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(run));
        var reaction = new FeishuOpenApiClient.FeishuMessageReaction(
                "reaction-1", "on_owner", "user", "1787900000000", "StatusInFlight");

        assertThat(service.triggerPolled("om_source", reaction, "https://wao.example")).isTrue();

        ArgumentCaptor<FeishuQuickCollectAiRunTriggerEntity> saved =
                ArgumentCaptor.forClass(FeishuQuickCollectAiRunTriggerEntity.class);
        verify(triggers).updateById(saved.capture());
        assertThat(saved.getValue().getEventKey()).isEqualTo("reaction-id:reaction-1");
        assertThat(saved.getValue().getEventId()).isNull();
        assertThat(saved.getValue().getAiRunId()).isEqualTo("ar-polled");
    }

    private JsonNode event(String eventId, String emoji, String openId) throws Exception {
        return json.readTree("""
                {"header":{"event_id":"%s","event_type":"im.message.reaction.created_v1"},"event":{
                  "message_id":"om_source","operator_type":"user","user_id":{"open_id":"%s"},
                  "reaction_type":{"emoji_type":"%s"},"action_time":"1787900000000"}}
                """.formatted(eventId, openId, emoji));
    }
}
