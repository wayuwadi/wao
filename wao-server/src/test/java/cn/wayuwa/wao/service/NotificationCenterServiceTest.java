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
    private final NotificationCenterService service = new NotificationCenterService(center, preferences);

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
        when(center.recentEvents(30)).thenReturn(List.of(event));
        when(center.loginUsage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(usage));
        when(center.countEventsSince(org.mockito.ArgumentMatchers.any())).thenReturn(4L);

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
        verify(center).countEventsSince(since.capture());
        assertThat(since.getValue()).isBetween(LocalDateTime.now().minusDays(7).minusMinutes(1),
                LocalDateTime.now().minusDays(7).plusMinutes(1));
    }

    @Test
    void honorsStoredReadWatermarkAndPersistsMarkReadPerMember() {
        String stored = "2026-08-27T08:15:30+08:00";
        when(preferences.find("wd", NotificationCenterService.READ_AT_KEY)).thenReturn(stored);
        when(center.recentEvents(30)).thenReturn(List.of());
        when(center.loginUsage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        var result = service.get("wd");

        assertThat(result.readAt()).isEqualTo(OffsetDateTime.parse(stored));
        verify(center).countEventsSince(LocalDateTime.of(2026, 8, 27, 8, 15, 30));

        OffsetDateTime marked = service.markRead("wd");
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(preferences).upsert(eq("wd"), eq(NotificationCenterService.READ_AT_KEY), value.capture());
        assertThat(OffsetDateTime.parse(value.getValue())).isEqualTo(marked);
    }
}
