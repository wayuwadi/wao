package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.MemberLoginUsageRow;
import cn.wayuwa.wao.domain.NotificationEventRow;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.NotificationCenterMapper;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationCenterServiceTest {
    private final NotificationCenterMapper center = mock(NotificationCenterMapper.class);
    private final MemberPreferenceMapper preferences = mock(MemberPreferenceMapper.class);
    private final NotificationSettingsService notificationSettings = mock(NotificationSettingsService.class);
    private final NotificationCenterService service = new NotificationCenterService(
            center, preferences, notificationSettings);

    private cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy allEvents() {
        return new cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy(true, true, true, true, true, true);
    }

    @Test
    void returnsGroundedEventsUsageAndUsesSevenDayUnreadWindowForFirstVisit() {
        NotificationEventRow event = new NotificationEventRow();
        event.setEventId("task-created:t1");
        event.setEventType("TASK_CREATED");
        event.setTitle("建设通知中心");
        event.setDetail("创建了任务");
        event.setActorName("娃哥");
        event.setOccurredAt(LocalDateTime.of(2026, 8, 28, 10, 0));
        event.setTargetPath("/projects/p1?task=t1");
        event.setSeverity("INFO");
        MemberLoginUsageRow usage = new MemberLoginUsageRow();
        usage.setMemberId("wg");
        usage.setMemberName("娃哥");
        usage.setColor("#4358D6");
        usage.setCount7Days(3);
        usage.setCount30Days(8);
        usage.setLastLoginAt(LocalDateTime.of(2026, 8, 28, 9, 0));
        when(notificationSettings.personalPolicy("wg")).thenReturn(allEvents());
        when(center.recentEvents(120)).thenReturn(List.of(event));
        when(center.eventsSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(event, event, event, event));
        when(center.loginUsage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(usage));

        var result = service.get("wg");

        assertThat(result.events()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("task-created:t1");
            assertThat(item.actorName()).isEqualTo("娃哥");
            assertThat(item.occurredAt().getOffset().toString()).isEqualTo("+08:00");
        });
        assertThat(result.loginUsage()).singleElement().satisfies(item -> {
            assertThat(item.count7Days()).isEqualTo(3);
            assertThat(item.count30Days()).isEqualTo(8);
        });
        assertThat(result.unreadCount()).isEqualTo(4);
        assertThat(result.readAt()).isNull();
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(center).eventsSince(since.capture());
        assertThat(since.getValue()).isBetween(LocalDateTime.now().minusDays(7).minusMinutes(1),
                LocalDateTime.now().minusDays(7).plusMinutes(1));
    }

    @Test
    void honorsStoredReadWatermarkAndPersistsMarkReadPerMember() {
        String stored = "2026-08-27T08:15:30+08:00";
        when(preferences.find("wd", NotificationCenterService.READ_AT_KEY)).thenReturn(stored);
        when(notificationSettings.personalPolicy("wd")).thenReturn(allEvents());
        when(center.recentEvents(120)).thenReturn(List.of());
        when(center.eventsSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(center.loginUsage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var result = service.get("wd");

        assertThat(result.readAt()).isEqualTo(OffsetDateTime.parse(stored));
        verify(center).eventsSince(LocalDateTime.of(2026, 8, 27, 8, 15, 30));

        OffsetDateTime marked = service.markRead("wd");
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(preferences).upsert(eq("wd"), eq(NotificationCenterService.READ_AT_KEY), value.capture());
        assertThat(OffsetDateTime.parse(value.getValue())).isEqualTo(marked);
    }

    @Test
    void personalPolicyFiltersTheListAndUnreadCountWithTheSameRules() {
        NotificationEventRow success = aiEvent("ai-run:ok", "SUCCESS", "AI 运行成功");
        NotificationEventRow failure = aiEvent("ai-run:failed", "WARNING", "AI 运行失败");
        when(notificationSettings.personalPolicy("wd")).thenReturn(
                new cn.wayuwa.wao.dto.NotificationSettingsDto.PersonalPolicy(
                        true, false, false, false, true, false));
        when(center.recentEvents(120)).thenReturn(List.of(success, failure));
        when(center.eventsSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(success, failure));
        when(center.loginUsage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var result = service.get("wd");

        assertThat(result.events()).extracting("id").containsExactly("ai-run:failed");
        assertThat(result.unreadCount()).isEqualTo(1);
    }

    private static NotificationEventRow aiEvent(String id, String severity, String detail) {
        NotificationEventRow event = new NotificationEventRow();
        event.setEventId(id);
        event.setEventType("AI_RUN");
        event.setTitle("通知优化");
        event.setDetail(detail);
        event.setSeverity(severity);
        event.setOccurredAt(LocalDateTime.of(2026, 8, 28, 10, 0));
        return event;
    }
}
