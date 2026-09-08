package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.domain.FeishuNotificationFeedbackEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.FeishuNotificationFeedbackMapper;
import cn.wayuwa.wao.mapper.FeishuNotificationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationFeedbackServiceTest {
    private final FeishuNotificationFeedbackMapper mapper = mock(FeishuNotificationFeedbackMapper.class);
    private final AutomationJobMapper jobs = mock(AutomationJobMapper.class);
    private final FeishuNotificationMapper notifications = mock(FeishuNotificationMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final NotificationFeedbackService service = new NotificationFeedbackService(mapper, jobs, notifications, settings, feishu);
    private final ObjectMapper json = new ObjectMapper();
    private final FeishuNotificationEntity notification = new FeishuNotificationEntity();
    private final List<FeishuNotificationFeedbackEntity> rows = new ArrayList<>();

    @BeforeEach
    void setup() {
        notification.setId("n1"); notification.setJobId("job1");
        notification.setSentAt(LocalDateTime.of(2026, 9, 8, 18, 0));
        notification.setMessageId("om_original"); notification.setCardTitle("下班休息与打卡提醒");
        notification.setCardBody("忙了一天，记得休息并打卡。");
        when(notifications.lockById("n1")).thenReturn(notification);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.feedbackCard(anyString(), any(), anyString(), anyString())).thenCallRealMethod();
        when(mapper.insert(any(FeishuNotificationFeedbackEntity.class))).thenAnswer(call -> { rows.add(call.getArgument(0)); return 1; });
        when(mapper.selectList(any())).thenAnswer(call -> List.copyOf(rows));
    }

    private JsonNode event(String who, String action, String message) throws Exception {
        var event = json.createObjectNode();
        event.putObject("operator").put("open_id", who).put("user_id", "id-" + who);
        event.putObject("context").put("open_message_id", message);
        event.putObject("action").putObject("value").put("kind", "automation-feedback")
                .put("notificationId", "n1").put("action", action).put("open_id", "ou_spoof");
        return event;
    }

    @Test
    void twoMembersDisplayTheirOwnChoicesUsingVerifiedIdentity() throws Exception {
        assertThat(service.collect(event("ou_brother", "done", "om_original")).displayed()).isTrue();
        assertThat(service.collect(event("ou_younger", "missed", "om_original")).displayed()).isTrue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getOperatorOpenId()).isEqualTo("ou_brother");
        assertThat(rows.get(1).getOperatorOpenId()).isEqualTo("ou_younger");
        var cards = ArgumentCaptor.forClass(java.util.Map.class);
        verify(feishu, times(2)).updateFeedbackCard(eq("token"), eq("om_original"), cards.capture());
        String card = json.writeValueAsString(cards.getValue());
        assertThat(card).contains("✅ <at id=ou_brother></at>：做到了", "❌ <at id=ou_younger></at>：没做到",
                "下班休息与打卡提醒", "忙了一天，记得休息并打卡。").doesNotContain("ou_spoof");
        verifyNoInteractions(jobs);
        verify(feishu, never()).addMessageReaction(any(), any(), any());
    }

    @Test
    void changingChoiceUpdatesOnlyThatMember() throws Exception {
        service.collect(event("ou_brother", "done", "om_original"));
        service.collect(event("ou_younger", "done", "om_original"));
        when(mapper.selectOne(any())).thenReturn(rows.get(0));
        var result = service.collect(event("ou_brother", "missed", "om_original"));
        assertThat(result.outcome()).isEqualTo("没做到");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getAction()).isEqualTo("missed");
        assertThat(rows.get(1).getAction()).isEqualTo("done");
        verify(mapper).updateById(rows.get(0));
        verify(notifications, times(3)).lockById("n1");
    }

    @Test
    void displayFailureKeepsFeedbackAndRetryUpdatesExistingRow() throws Exception {
        doThrow(new FeishuApiException("显示失败")).when(feishu).updateFeedbackCard(any(), any(), any());
        assertThat(service.collect(event("ou_brother", "done", "om_original")))
                .isEqualTo(new NotificationFeedbackService.Result("做到了", false));
        assertThat(rows).hasSize(1);
        when(mapper.selectOne(any())).thenReturn(rows.get(0));
        doNothing().when(feishu).updateFeedbackCard(any(), any(), any());
        assertThat(service.collect(event("ou_brother", "done", "om_original")).displayed()).isTrue();
        assertThat(rows).hasSize(1);
    }

    @Test
    void legacyCardCreatesOneSharedReplyThenUpdatesItFromEitherCard() throws Exception {
        notification.setCardTitle(null); notification.setCardBody(null); notification.setMessageId(null);
        when(feishu.replyFeedbackCard(any(), any(), any(), any())).thenReturn("om_reply");
        assertThat(service.collect(event("ou_brother", "done", "om_original")).displayed()).isTrue();
        assertThat(notification.getMessageId()).isEqualTo("om_original");
        assertThat(notification.getFeedbackMessageId()).isEqualTo("om_reply");
        service.collect(event("ou_younger", "missed", "om_reply"));
        when(mapper.selectOne(any())).thenReturn(rows.get(0));
        service.collect(event("ou_brother", "missed", "om_original"));
        verify(feishu).replyFeedbackCard(eq("token"), eq("om_original"), any(), eq("feedback-n1"));
        verify(feishu, times(2)).updateFeedbackCard(eq("token"), eq("om_reply"), any());
        verify(notifications).updateById(notification);
    }

    @Test
    void unknownNotificationAndMismatchedMessageDoNotCreateFeedback() throws Exception {
        assertThat(service.collect(event("ou_brother", "done", "om_other"))).isNull();
        when(notifications.lockById("n1")).thenReturn(null);
        assertThat(service.collect(event("ou_brother", "done", "om_original"))).isNull();
        verifyNoInteractions(mapper, feishu);
    }

    @Test
    void invalidIdentityOrOutcomeCannotInjectMentionsOrCreateFeedback() throws Exception {
        for (String id : List.of("", "all", "ou_bad></at><at id=all", "ou_bad\n")) {
            assertThat(service.collect(event(id, "done", "om_original"))).isNull();
        }
        assertThat(service.collect(event("ou_brother", "unknown", "om_original"))).isNull();
        verifyNoInteractions(mapper, notifications, feishu);
    }

    @Test
    void missingMessageOrCredentialsPersistsChoiceWithoutClaimingDisplaySuccess() throws Exception {
        assertThat(service.collect(event("ou_brother", "done", "")).displayed()).isFalse();
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("");
        assertThat(service.collect(event("ou_younger", "done", "om_original")).displayed()).isFalse();
        assertThat(rows).hasSize(2);
        verifyNoInteractions(feishu);
    }
}
