package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.AutomationJobEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class WorkdayFeishuNotificationHandlerTest {
    private final FeishuNotificationHandler delegate = mock(FeishuNotificationHandler.class);
    private final WorkdayFeishuNotificationHandler handler =
            new WorkdayFeishuNotificationHandler(new HolidayService(), delegate);
    private final AutomationJobEntity job = new AutomationJobEntity();

    @Test
    void sendsOnWeekdayAndOfficialAdjustedWorkday() {
        when(delegate.execute(job)).thenReturn("成功 · 已发送飞书通知");

        assertThat(handler.execute(job, LocalDateTime.of(2026, 5, 8, 18, 0))).startsWith("成功");
        assertThat(handler.execute(job, LocalDateTime.of(2026, 5, 9, 18, 0))).startsWith("成功");

        verify(delegate, org.mockito.Mockito.times(2)).execute(job);
    }

    @Test
    void skipsOfficialHolidayAndWeekendWithoutSending() {
        assertThat(handler.execute(job, LocalDateTime.of(2026, 5, 1, 18, 0)))
                .isEqualTo("已跳过 · 2026-05-01 为休息日");
        assertThat(handler.execute(job, LocalDateTime.of(2026, 5, 10, 18, 0)))
                .isEqualTo("已跳过 · 2026-05-10 为休息日");

        verify(delegate, never()).execute(job);
    }

    @Test
    void manualTestStillUsesTheRealSender() {
        when(delegate.execute(job)).thenReturn("成功 · 已发送飞书通知");

        assertThat(handler.execute(job, null)).startsWith("成功");

        verify(delegate).execute(job);
    }
}
