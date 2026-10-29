package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.service.NavigationLayoutService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FixedNavigationControllerTest {
    @Test
    void legacyLayoutWritesDoNotReachPersistence() {
        var layouts = mock(NavigationLayoutService.class);
        var controller = new NavigationLayoutController(layouts, mock(CurrentMember.class));
        assertThatThrownBy(() -> controller.put(null, mock(HttpServletRequest.class)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("统一维护");
        verifyNoInteractions(layouts);
    }

    @Test
    void genericPreferencesCannotBypassFixedNavigationAndOtherPreferencesStillWork() {
        var mapper = mock(MemberPreferenceMapper.class);
        var member = mock(CurrentMember.class);
        var http = mock(HttpServletRequest.class);
        when(member.idOr(http, "wg")).thenReturn("alice");
        var controller = new MemberPreferenceController(mapper, member);
        for (String key : new String[]{NavigationLayoutService.PREFERENCE_KEY, "nav-personal-order",
                "mobile-bottom-nav-v1", "nav-top-shortcuts-v1", "quick-links"}) {
            assertThatThrownBy(() -> controller.put(key, Map.of("value", "[]"), http))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("统一维护");
        }
        verifyNoInteractions(mapper);
        controller.put("unrelated-preference", Map.of("value", "unchanged"), http);
        verify(mapper).upsert("alice", "unrelated-preference", "unchanged");
    }
}
