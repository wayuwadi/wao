package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.domain.FeishuNotificationEntity;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.mapper.FeishuNotificationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:feedbackidentity;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql(statements = {"DROP TABLE IF EXISTS feishu_notification_feedback", "DROP TABLE IF EXISTS feishu_notification"})
@Sql(scripts = {"/db/migration/V43__feishu_notification_feedback.sql", "/db/migration/V146__notification_feedback_identity.sql"})
class NotificationFeedbackIntegrationTest {
    @Autowired NotificationFeedbackService service;
    @Autowired FeishuNotificationMapper notifications;
    @Autowired JdbcTemplate jdbc;
    @MockBean FeishuOpenApiClient feishu;
    @MockBean SettingService settings;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void prepare() {
        var n = new FeishuNotificationEntity();
        n.setId("n1"); n.setJobId("job1"); n.setSentAt(LocalDateTime.now());
        n.setMessageId("om_original"); n.setCardTitle("提醒"); n.setCardBody("原始正文");
        notifications.insert(n);
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
        when(feishu.feedbackCard(anyString(), any(), anyString(), anyString())).thenCallRealMethod();
    }

    private NotificationFeedbackService.Result click(String who, String outcome) throws Exception {
        return service.collect(json.readTree("""
                {"operator":{"open_id":"%s"},"context":{"open_message_id":"om_original"},
                 "action":{"value":{"kind":"automation-feedback","notificationId":"n1","action":"%s"}}}
                """.formatted(who, outcome)));
    }

    @Test void actualTransactionCommitsChoiceDespiteDisplayFailure() throws Exception {
        doThrow(new FeishuApiException("显示失败")).when(feishu).updateFeedbackCard(any(), any(), any());
        assertThat(click("ou_one", "done").displayed()).isFalse();
        assertThat(jdbc.queryForObject("SELECT action FROM feishu_notification_feedback WHERE operator_open_id='ou_one'", String.class)).isEqualTo("done");
        doNothing().when(feishu).updateFeedbackCard(any(), any(), any());
        assertThat(click("ou_one", "missed").displayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feishu_notification_feedback", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT action FROM feishu_notification_feedback", String.class)).isEqualTo("missed");
    }

    @Test void concurrentMembersCannotOverwriteEachOthersSummary() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        List<String> cards = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            cards.add(json.writeValueAsString(call.getArgument(2)));
            if (cards.size() == 1) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            }
            return null;
        }).when(feishu).updateFeedbackCard(any(), any(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> click("ou_one", "done"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var two = pool.submit(() -> click("ou_two", "missed"));
            release.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).displayed()).isTrue();
            assertThat(two.get(10, TimeUnit.SECONDS).displayed()).isTrue();
            assertThat(cards).hasSize(2);
            assertThat(cards.get(1)).contains("✅ <at id=ou_one></at>：做到了", "❌ <at id=ou_two></at>：没做到");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM feishu_notification_feedback", Integer.class)).isEqualTo(2);
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }
}
