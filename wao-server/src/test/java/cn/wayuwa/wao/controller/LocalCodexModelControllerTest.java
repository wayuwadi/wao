package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.service.LocalCodexModelService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LocalCodexModelControllerTest {
    @Test void anonymousRequestsDoNotStartCodex() {
        var service = mock(LocalCodexModelService.class); var member = mock(CurrentMember.class);
        var request = new MockHttpServletRequest();
        var controller = new LocalCodexModelController(service, member);
        assertThatThrownBy(() -> controller.get(request)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(service);
        when(member.id(request)).thenReturn("test-member");
        controller.get(request); verify(service).catalog();
    }
}
