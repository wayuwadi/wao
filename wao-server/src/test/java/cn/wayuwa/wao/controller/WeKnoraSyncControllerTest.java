package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WeKnoraSyncControllerTest {
    final WeKnoraSyncService service=mock(WeKnoraSyncService.class);
    final CurrentMember current=mock(CurrentMember.class);
    final NotificationSettingsService permissions=mock(NotificationSettingsService.class);
    final WeKnoraSyncController controller=new WeKnoraSyncController(service,current,permissions);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    @Test void unauthenticatedAndNonAdminCannotReadOrTriggerPersonalExports() {
        assertThrows(UnauthorizedException.class,()->controller.get(request));
        when(current.id(request)).thenReturn("other-member");
        assertThrows(UnauthorizedException.class,()->controller.run(request));
        assertThrows(UnauthorizedException.class,()->controller.source("docs:d1:1",request));
        verifyNoInteractions(service);
    }
    @Test void ownerIsAlwaysTakenFromServerSession() {
        when(current.id(request)).thenReturn("actual-owner");when(permissions.canManageSystem("actual-owner")).thenReturn(true);
        when(service.overview("actual-owner")).thenReturn(Map.of());
        controller.get(request);verify(service).overview("actual-owner");
    }
}
