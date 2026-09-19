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
    @Test void unauthenticatedMembersCannotReadOrTriggerSync() {
        assertThrows(UnauthorizedException.class,()->controller.get(request));
        assertThrows(UnauthorizedException.class,()->controller.run(request));
        assertThrows(UnauthorizedException.class,()->controller.documents(java.util.List.of("d1"),request));
        assertThrows(UnauthorizedException.class,()->controller.knowledgeRun(request));
        verifyNoInteractions(service);
    }
    @Test void everySignedInMemberCanViewPreviewAndTriggerSharedSync() {
        when(current.id(request)).thenReturn("regular-member");
        when(service.overview("regular-member")).thenReturn(Map.of());
        when(service.enqueue("manual","regular-member")).thenReturn("run-1");
        controller.get(request);controller.progress(request);controller.source("chat:2026-09-15:1",request);controller.run(request);
        verify(service).overview("regular-member");
        verify(service).progress("regular-member");
        verify(service).source("regular-member","chat:2026-09-15:1");
        verify(service).enqueue("manual","regular-member");
        verify(permissions,never()).canManageSystem(anyString());
    }
    @Test void onlyAdministratorsCanChangeSharedSyncSettings() {
        when(current.id(request)).thenReturn("regular-member");
        var settings=new WeKnoraSyncService.Settings("8f7e78c1-4933-4dec-bce3-f35dd8f4e698","",java.util.List.of("chat"),20,true,false,"02:00",0);
        assertThrows(UnauthorizedException.class,()->controller.save(settings,request));
        verifyNoInteractions(service);
        when(permissions.canManageSystem("regular-member")).thenReturn(true);
        controller.save(settings,request);
        verify(service).save("regular-member",settings);
    }
}
