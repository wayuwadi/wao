package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.TianrenDailyAutomationService;
import cn.wayuwa.wao.service.TianrenReadingService;
import cn.wayuwa.wao.service.WorkdayRhythmDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TianrenControllerTest {
    @Test void aiStatusRequiresLoginAndUsesOnlyCurrentMember() {
        var automation = mock(TianrenDailyAutomationService.class);
        var member = mock(CurrentMember.class);
        var controller = new TianrenController(mock(TianrenReadingService.class), automation,
                member, mock(WorkdayRhythmDashboardService.class));
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> controller.aiStatus(request)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(automation);
        when(member.id(request)).thenReturn("synthetic-member");
        controller.aiStatus(request);
        verify(automation).status("synthetic-member");
        verifyNoMoreInteractions(automation);
    }
}
