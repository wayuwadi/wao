package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AutomationJobDto;
import cn.wayuwa.wao.dto.CreateJobRequest;
import cn.wayuwa.wao.service.AutomationService;
import cn.wayuwa.wao.service.FeishuNotificationHandler;
import cn.wayuwa.wao.service.NotificationSettingsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AutomationControllerTest {
    private final AutomationService automation = mock(AutomationService.class);
    private final CurrentMember currentMember = mock(CurrentMember.class);
    private final NotificationSettingsService settings = mock(NotificationSettingsService.class);
    private final AutomationController controller = new AutomationController(automation, currentMember, settings);
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    @Test
    void genericAutomationEndpointsCannotBypassReminderAdministratorGuard() {
        when(currentMember.id(http)).thenReturn("member");
        when(automation.getJob("reminder")).thenReturn(new AutomationJobDto(
                "reminder", "提醒", "ri-alarm-line", "每日 08:50", "正常", "尚未运行", "正文", "", "",
                List.of(), List.of(), "0 50 8 * * *", null, FeishuNotificationHandler.NAME));

        assertThatThrownBy(() -> controller.run("reminder", http))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("只有管理员");
        assertThatThrownBy(() -> controller.create(new CreateJobRequest(
                "提醒", null, null, "正文", null, null, List.of(), "0 50 8 * * *",
                FeishuNotificationHandler.NAME), http))
                .isInstanceOf(UnauthorizedException.class);

        verify(automation, never()).runJob("reminder", "member");
        verify(automation, never()).createJob(org.mockito.ArgumentMatchers.any());
    }
    @Test
    void knowledgeSyncCannotBeTriggeredByNonAdminThroughGenericAutomation() {
        when(currentMember.id(http)).thenReturn("member");
        when(automation.getJob("sync")).thenReturn(new AutomationJobDto(
                "sync", "同步", "", "", "正常", "", "", "", "",
                List.of(), List.of(), "0 0 2 * * *", null,
                cn.wayuwa.wao.service.WeKnoraSyncService.HANDLER));
        assertThatThrownBy(() -> controller.run("sync", http)).isInstanceOf(UnauthorizedException.class);
        verify(automation, never()).runJob("sync", "member");
    }

}
