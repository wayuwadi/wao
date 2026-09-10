package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.feishu.FeishuWebhookNotifier.Card;
import cn.wayuwa.wao.mapper.InternalNotificationMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class InternalNotificationServiceTest {
    private final InternalNotificationMapper mapper = mock(InternalNotificationMapper.class);
    private final InternalNotificationService service = new InternalNotificationService(mapper);

    @Test void savesTitleFieldsBodyAndLocalTaskLink() {
        service.publish(new Card("任务更新", "blue", List.of(List.of("任务", "测试任务")),
                "需要验收", "查看", "https://wao.wayuwa.cn/projects/p1?task=t1"));
        verify(mapper).insert(anyString(), isNull(), eq("任务更新"), eq("任务：测试任务\n需要验收"), eq("/projects/p1?task=t1"));
    }

    @Test void externalOrExecutableLinksCannotBecomeNotificationTargets() {
        for (String url : List.of("javascript:alert(1)", "//evil.test", "https://evil.test/private")) {
            service.publish(new Card("提醒", "blue", List.of(), "正文", "打开", url));
        }
        verify(mapper, times(3)).insert(anyString(), isNull(), eq("提醒"), eq("正文"), isNull());
    }

    @Test void personalReminderKeepsItsOwnerScope() {
        service.publishToMember("member1", Card.simple("日历提醒", "blue", "私人日历"));
        verify(mapper).insert(anyString(), eq("member1"), eq("日历提醒"), eq("私人日历"), isNull());
    }

    @Test void failedPersistenceDoesNotReportDeliverySuccess() {
        when(mapper.insert(anyString(), any(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.publish(Card.simple("提醒", "blue", "正文")))
                .isInstanceOf(IllegalStateException.class);
    }
}
