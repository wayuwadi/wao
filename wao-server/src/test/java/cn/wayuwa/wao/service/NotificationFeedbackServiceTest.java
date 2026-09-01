package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.domain.FeishuNotificationFeedbackEntity;
import cn.wayuwa.wao.mapper.AutomationJobMapper;
import cn.wayuwa.wao.mapper.FeishuNotificationFeedbackMapper;
import cn.wayuwa.wao.mapper.FeishuNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationFeedbackServiceTest {

    private final FeishuNotificationFeedbackMapper feedbackMapper = mock(FeishuNotificationFeedbackMapper.class);
    private final AutomationJobMapper jobs = mock(AutomationJobMapper.class);
    private final FeishuNotificationMapper notifications = mock(FeishuNotificationMapper.class);
    private final NotificationFeedbackService service =
            new NotificationFeedbackService(feedbackMapper, jobs, notifications);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void persistsOfficialCardCallbackIdentityAndOutcome() throws Exception {
        FeishuNotificationEntity notification = new FeishuNotificationEntity();
        notification.setId("n1");
        notification.setJobId("job-reminder-lunch");
        notification.setSentAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        when(notifications.selectById("n1")).thenReturn(notification);
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        when(feedbackMapper.selectById(any())).thenReturn(null);

        String result = service.collect(json.readTree("""
                {
                  "operator":{"open_id":"ou_member","user_id":"member-1"},
                  "action":{"value":{"kind":"automation-feedback","notificationId":"n1","action":"done"}}
                }
                """));

        assertThat(result).isEqualTo("做到了");
        ArgumentCaptor<FeishuNotificationFeedbackEntity> captor =
                ArgumentCaptor.forClass(FeishuNotificationFeedbackEntity.class);
        verify(feedbackMapper).insert(captor.capture());
        assertThat(captor.getValue()).extracting(
                FeishuNotificationFeedbackEntity::getNotificationId,
                FeishuNotificationFeedbackEntity::getJobId,
                FeishuNotificationFeedbackEntity::getOperatorOpenId,
                FeishuNotificationFeedbackEntity::getOperatorName,
                FeishuNotificationFeedbackEntity::getAction)
                .containsExactly("n1", "job-reminder-lunch", "ou_member", "member-1", "done");
    }

    @Test
    void rejectsUnknownNotificationWithoutCreatingFeedback() throws Exception {
        when(notifications.selectById("n-missing")).thenReturn(null);

        String result = service.collect(json.readTree("""
                {
                  "operator":{"open_id":"ou_member"},
                  "action":{"value":{"kind":"automation-feedback","notificationId":"n-missing","action":"missed"}}
                }
                """));

        assertThat(result).isNull();
        verify(notifications).selectById("n-missing");
    }
}
