package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.CompletePendingRhythmFocusRequest;
import cn.wayuwa.wao.service.PendingRhythmFocusService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PendingRhythmFocusControllerTest {
    private final CurrentMember member = mock(CurrentMember.class);
    private final PendingRhythmFocusService service = mock(PendingRhythmFocusService.class);
    private final PendingRhythmFocusController controller = new PendingRhythmFocusController(member, service);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void anonymousReadAndWriteDoNotFallBackToAnotherMember() {
        assertThatThrownBy(() -> controller.pending(request)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.complete(LocalDate.of(2026, 1, 1), 0,
                new CompletePendingRhythmFocusRequest("重点", true), request)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void bothReadAndWriteUseTheSessionMember() {
        when(member.id(request)).thenReturn("m2");
        controller.pending(request);
        controller.complete(LocalDate.of(2026, 1, 1), 0,
                new CompletePendingRhythmFocusRequest("重点", false), request);
        verify(service).pending(eq("m2"), any(LocalDate.class));
        verify(service).complete(eq("m2"), eq(LocalDate.of(2026, 1, 1)), any(LocalDate.class), eq(0), eq("重点"), eq(false));
    }
}
