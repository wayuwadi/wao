package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AuthSessionDto;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.service.AccountAuthService;
import cn.wayuwa.wao.service.DeepSeekBudgetService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DeepSeekPolicyControllerTest {
    private final DeepSeekBudgetService budget = mock(DeepSeekBudgetService.class);
    private final AccountAuthService auth = mock(AccountAuthService.class);
    private final AuthSessionManager sessions = mock(AuthSessionManager.class);
    private final DeepSeekPolicyController controller = new DeepSeekPolicyController(budget, auth, sessions);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test void unauthenticatedAndOrdinaryMembersCannotReadOrChangeGlobalBudget() {
        assertThatThrownBy(() -> controller.get(request)).isInstanceOf(UnauthorizedException.class);
        session("成员");
        assertThatThrownBy(() -> controller.get(request)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.save(request, null)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(budget);
    }

    @Test void adminCanSavePolicy() {
        session("超级管理员");
        var policy = new DeepSeekBudgetService.Policy(true, 10000000, true, false, false, false);
        controller.save(request, policy);
        verify(budget).save(policy);
    }

    private void session(String role) {
        when(sessions.currentAccountId(request)).thenReturn("a1");
        var value = mock(AuthSessionDto.class);
        var member = mock(MemberDto.class);
        when(value.member()).thenReturn(member);
        when(member.role()).thenReturn(role);
        when(auth.session("a1")).thenReturn(value);
    }
}
