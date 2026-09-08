package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.dto.NotificationReminderDto;
import cn.wayuwa.wao.dto.PatchJobRequest;
import cn.wayuwa.wao.dto.SaveNotificationReminderRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationReminderServiceTest {
    private final AutomationService automation = mock(AutomationService.class);
    private final NotificationSettingsService settings = mock(NotificationSettingsService.class);
    private final NotificationReminderService service = new NotificationReminderService(automation, settings);

    @BeforeEach
    void allowAdministrator() {
        when(settings.canManageSystem("admin")).thenReturn(true);
    }

    @Test
    void createsDailyReminderWithDeterministicSpringCron() {
        when(automation.createJob(org.mockito.ArgumentMatchers.any())).thenReturn(
                job("j-new", "午餐提醒", "到午饭时间了", "0 0 12 * * *", "正常"));

        NotificationReminderDto result = service.create("admin",
                new SaveNotificationReminderRequest(" 午餐提醒 ", " 到午饭时间了 ", "12:00", true));

        ArgumentCaptor<CreateJobRequest> request = ArgumentCaptor.captor();
        verify(automation).createJob(request.capture());
        assertThat(request.getValue().cronExpr()).isEqualTo("0 0 12 * * *");
        assertThat(request.getValue().handler()).isEqualTo(FeishuNotificationHandler.NAME);
        assertThat(result.time()).isEqualTo("12:00");
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void savesTimeAndPauseStateTogether() {
        when(automation.getJob("j1")).thenReturn(job("j1", "旧标题", "旧正文", "0 50 8 * * *", "正常"));
        when(automation.updateJob(org.mockito.ArgumentMatchers.eq("j1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(job("j1", "新标题", "新正文", "0 30 12 * * *", "正常"));
        when(automation.pauseJob("j1"))
                .thenReturn(job("j1", "新标题", "新正文", "0 30 12 * * *", "已暂停"));

        NotificationReminderDto result = service.update("admin", "j1",
                new SaveNotificationReminderRequest("新标题", "新正文", "12:30", false));

        ArgumentCaptor<PatchJobRequest> request = ArgumentCaptor.captor();
        verify(automation).updateJob(org.mockito.ArgumentMatchers.eq("j1"), request.capture());
        assertThat(request.getValue().cronExpr()).isEqualTo("0 30 12 * * *");
        verify(automation).pauseJob("j1");
        assertThat(result.enabled()).isFalse();
    }

    @Test
    void rejectsNonAdministratorInvalidFieldsAndNonReminderTargets() {
        assertThatThrownBy(() -> service.create("member",
                new SaveNotificationReminderRequest("标题", "正文", "08:50", true)))
                .isInstanceOf(UnauthorizedException.class);

        assertThatThrownBy(() -> service.create("admin",
                new SaveNotificationReminderRequest("标题", "", "08:50", true)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("内容不能为空");

        when(automation.getJob("other")).thenReturn(new AutomationJobDto(
                "other", "巡检", "ri-speed", "每日", "正常", "尚未运行", "", "", "", List.of(),
                List.of(), "0 0 1 * * *", LocalDateTime.now(), "http-performance-ai-run"));
        assertThatThrownBy(() -> service.delete("admin", "other"))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("每日提醒不存在");
    }

    @Test
    void listsOnlyRealNotificationHandlersInTimeOrder() {
        when(automation.listJobs()).thenReturn(List.of(
                job("j2", "午餐", "吃饭", "0 0 12 * * *", "正常"),
                new AutomationJobDto("other", "巡检", "ri-speed", "每日", "正常", "尚未运行", "", "", "",
                        List.of(), List.of(), "0 30 6 * * *", null, "http-performance-ai-run"),
                job("j1", "打卡", null, "0 50 8 * * *", "已暂停")));

        var result = service.get("admin");

        assertThat(result.canManageSystem()).isTrue();
        assertThat(result.reminders()).extracting(NotificationReminderDto::id).containsExactly("j1", "j2");
        assertThat(result.reminders().get(0).enabled()).isFalse();
        assertThat(result.reminders().get(0).content()).isEmpty();
    }

    @Test
    void keepsWorkdayCadenceWhenEditingClockOutReminder() {
        when(automation.getJob("clock-out")).thenReturn(
                workdayJob("clock-out", "下班休息与打卡提醒", "休息并打卡", "0 0 18 * * *", "正常"));
        when(automation.updateJob(org.mockito.ArgumentMatchers.eq("clock-out"),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                workdayJob("clock-out", "下班提醒", "记得休息", "0 30 18 * * *", "正常"));

        NotificationReminderDto result = service.update("admin", "clock-out",
                new SaveNotificationReminderRequest("下班提醒", "记得休息", "18:30", true));

        ArgumentCaptor<PatchJobRequest> request = ArgumentCaptor.captor();
        verify(automation).updateJob(org.mockito.ArgumentMatchers.eq("clock-out"), request.capture());
        assertThat(request.getValue().handler()).isEqualTo(WorkdayFeishuNotificationHandler.NAME);
        assertThat(request.getValue().schedule()).isEqualTo("工作日 18:30");
        assertThat(result.workdayOnly()).isTrue();
    }

    private AutomationJobDto job(String id, String title, String content, String cron, String status) {
        return new AutomationJobDto(id, title, "ri-alarm-line", "每日", status, "尚未运行", content,
                "通知设置 · 每日提醒", "飞书群", List.of(), List.of(), cron,
                LocalDateTime.of(2026, 9, 1, 8, 50), FeishuNotificationHandler.NAME);
    }

    private AutomationJobDto workdayJob(String id, String title, String content, String cron, String status) {
        return new AutomationJobDto(id, title, "ri-alarm-line", "工作日", status, "尚未运行", content,
                "通知设置 · 每日提醒", "飞书群", List.of(), List.of(), cron,
                LocalDateTime.of(2026, 9, 1, 18, 0), WorkdayFeishuNotificationHandler.NAME);
    }
}
