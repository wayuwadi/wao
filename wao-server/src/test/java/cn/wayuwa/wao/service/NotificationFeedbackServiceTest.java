package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.domain.FeishuNotificationFeedbackEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.mapper.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationFeedbackServiceTest {
    private final FeishuNotificationFeedbackMapper mapper = mock(FeishuNotificationFeedbackMapper.class);
    private final AutomationJobMapper jobs = mock(AutomationJobMapper.class);
    private final FeishuNotificationMapper notifications = mock(FeishuNotificationMapper.class);
    private final FeishuReactionAuthorizationService authorization = mock(FeishuReactionAuthorizationService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final NotificationFeedbackService service = new NotificationFeedbackService(mapper, jobs, notifications, authorization, feishu);
    private final ObjectMapper json = new ObjectMapper();
    private final FeishuNotificationEntity notification = new FeishuNotificationEntity();
    private final List<FeishuNotificationFeedbackEntity> rows = new ArrayList<>();

    @BeforeEach void setup() {
        notification.setId("n1"); notification.setJobId("job1"); notification.setSentAt(LocalDateTime.now());
        notification.setMessageId("om_original"); notification.setCardTitle("下班提醒");
        when(notifications.lockById("n1")).thenReturn(notification);
        when(authorization.accessToken("ou_brother")).thenReturn("user-brother-token");
        when(authorization.accessToken("ou_younger")).thenReturn("user-younger-token");
        when(mapper.insert(any(FeishuNotificationFeedbackEntity.class))).thenAnswer(call -> { rows.add(call.getArgument(0)); return 1; });
        when(feishu.addUserMessageReaction(any(), any(), any(), any())).thenAnswer(call -> "r-" + call.getArgument(3) + "-" + call.getArgument(2));
    }
    private JsonNode event(String who, String action, String message) throws Exception {
        var event = json.createObjectNode(); event.putObject("operator").put("open_id", who);
        event.putObject("context").put("open_message_id", message);
        event.putObject("action").putObject("value").put("kind", "automation-feedback")
                .put("notificationId", "n1").put("action", action).put("open_id", "ou_spoof");
        return event;
    }
    @Test void twoPeopleReactUsingTheirOwnCredentialsAndNeverSendMessages() throws Exception {
        assertThat(service.collect(event("ou_brother", "done", "om_original")).displayed()).isTrue();
        assertThat(service.collect(event("ou_younger", "done", "om_original")).displayed()).isTrue();
        verify(feishu).addUserMessageReaction("user-brother-token", "om_original", "CheckMark", "ou_brother");
        verify(feishu).addUserMessageReaction("user-younger-token", "om_original", "CheckMark", "ou_younger");
        verifyNoMoreInteractions(feishu);
        assertThat(rows).hasSize(2);
    }
    @Test void changingChoiceDeletesOnlyTheClickersPreviousReaction() throws Exception {
        service.collect(event("ou_brother", "done", "om_original"));
        service.collect(event("ou_younger", "done", "om_original"));
        when(mapper.selectOne(any())).thenReturn(rows.get(0));
        assertThat(service.collect(event("ou_brother", "missed", "om_original")).displayed()).isTrue();
        verify(feishu).deleteOwnFeedbackReaction("user-brother-token", "om_original", "r-ou_brother-CheckMark");
        verify(feishu).addUserMessageReaction("user-brother-token", "om_original", "CrossMark", "ou_brother");
        verify(feishu, never()).deleteOwnFeedbackReaction(eq("user-younger-token"), any(), any());
        assertThat(rows.get(1).getAction()).isEqualTo("done");
        assertThat(rows.get(0).getReactionAction()).isEqualTo("missed");
    }
    @Test void missingAuthorizationNeverBorrowsBotOrAnotherPersonsToken() throws Exception {
        when(authorization.accessToken("ou_brother")).thenReturn(null);
        var result = service.collect(event("ou_brother", "done", "om_original"));
        assertThat(result.displayed()).isFalse(); assertThat(result.hint()).contains("授权个人表情");
        assertThat(rows).hasSize(1); verifyNoInteractions(feishu);
    }
    @Test void oldSharedReplyButtonReactsToOriginalMessageWithoutMoreReplies() throws Exception {
        notification.setFeedbackMessageId("om_previous_reply");
        assertThat(service.collect(event("ou_brother", "done", "om_previous_reply")).displayed()).isTrue();
        verify(feishu).addUserMessageReaction("user-brother-token", "om_original", "CheckMark", "ou_brother");
        verifyNoMoreInteractions(feishu);
    }
    @Test void legacyNotificationBindsOriginalMessageEvenBeforeAuthorization() throws Exception {
        notification.setMessageId(null); notification.setCardTitle(null);
        when(authorization.accessToken("ou_brother")).thenReturn(null);
        service.collect(event("ou_brother", "done", "om_original"));
        assertThat(notification.getMessageId()).isEqualTo("om_original"); verify(notifications).updateById(notification);
        verifyNoInteractions(feishu);
    }
    @Test void failedApiKeepsChoiceAndAllowsSameUserToRetry() throws Exception {
        when(feishu.addUserMessageReaction(any(), any(), any(), any())).thenThrow(new FeishuApiException("失败"));
        assertThat(service.collect(event("ou_brother", "done", "om_original")).displayed()).isFalse();
        assertThat(rows).hasSize(1); when(mapper.selectOne(any())).thenReturn(rows.get(0));
        doReturn("r-retry").when(feishu).addUserMessageReaction(any(), any(), any(), any());
        assertThat(service.collect(event("ou_brother", "done", "om_original")).displayed()).isTrue();
        assertThat(rows).hasSize(1); assertThat(rows.get(0).getReactionId()).isEqualTo("r-retry");
    }
    @Test void invalidIdentityUnknownNotificationAndWrongMessageAreRejected() throws Exception {
        assertThat(service.collect(event("all", "done", "om_original"))).isNull();
        assertThat(service.collect(event("ou_brother", "unknown", "om_original"))).isNull();
        assertThat(service.collect(event("ou_brother", "done", "om_other"))).isNull();
        when(notifications.lockById("n1")).thenReturn(null);
        assertThat(service.collect(event("ou_brother", "done", "om_original"))).isNull();
        verifyNoInteractions(mapper, authorization, feishu);
    }
}
