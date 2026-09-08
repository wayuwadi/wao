package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.AuthSessionManager;
import cn.wayuwa.wao.auth.JumpServerSsoService;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.AuthSessionDto;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.service.AccountAuthService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountAuthControllerTest {

    private final AccountAuthService auth = mock(AccountAuthService.class);
    private final AuthSessionManager sessions = mock(AuthSessionManager.class);
    private final JumpServerSsoService jumpServerSso = mock(JumpServerSsoService.class);
    private final AccountAuthController controller = new AccountAuthController(auth, sessions, jumpServerSso);

    @Test
    void jenkinsForwardsStableMemberIdentityAndReadOnlyGroup() {
        MockHttpServletRequest request = requestFor("account-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(auth.session("account-1")).thenReturn(session("member-1", "成员"));

        controller.jenkins(request, response);

        assertThat(response.getHeader(AccountAuthController.JENKINS_USER_HEADER)).isEqualTo("member-1");
        assertThat(response.getHeader(AccountAuthController.JENKINS_GROUPS_HEADER)).isEqualTo("wao-members");
    }

    @Test
    void jenkinsAddsAdministratorGroupForBothAdministratorRoles() {
        for (String role : List.of("管理员", "超级管理员")) {
            MockHttpServletRequest request = requestFor("account-" + role);
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(auth.session("account-" + role)).thenReturn(session("member-" + role, role));

            controller.jenkins(request, response);

            assertThat(response.getHeader(AccountAuthController.JENKINS_GROUPS_HEADER))
                    .isEqualTo("wao-members|wao-admins");
        }
    }

    @Test
    void jenkinsRejectsRequestsWithoutWaoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.jenkins(request, new MockHttpServletResponse()))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("登录已过期，请重新登录");
    }

    @Test
    void jumpServerRedirectsAuthenticatedMemberToSignedLoginUrl() throws Exception {
        MockHttpServletRequest request = requestFor("account-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthSessionDto current = session("member-1", "成员");
        when(auth.session("account-1")).thenReturn(current);
        when(jumpServerSso.loginUrl(current)).thenReturn(
                "https://jms.wayuwa.cn/api/v1/authentication/custom-sso/login/?token=signed");

        controller.jumpServer(request, response);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "https://jms.wayuwa.cn/api/v1/authentication/custom-sso/login/?token=signed");
    }

    @Test
    void jumpServerRejectsRequestWithoutWaoSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.jumpServer(request, new MockHttpServletResponse()))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("登录已过期，请重新登录");
    }

    @Test
    void infrastructureRequiresAuthenticatedAdministrator() {
        assertThatThrownBy(() -> controller.infrastructure(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(UnauthorizedException.class);
        for (String role : List.of("成员", "管理员", "超级管理员")) {
            MockHttpServletRequest request = requestFor("infra-" + role);
            when(auth.session("infra-" + role)).thenReturn(session("member", role));
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.infrastructure(request, response);
            assertThat(response.getStatus()).isEqualTo("成员".equals(role) ? 403 : 200);
        }
    }

    private MockHttpServletRequest requestFor(String accountId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(sessions.currentAccountId(request)).thenReturn(accountId);
        return request;
    }

    private AuthSessionDto session(String memberId, String role) {
        return new AuthSessionDto("account", new MemberDto(memberId, "测试成员", "测", "#4358D6", role),
                List.of(), LocalDateTime.of(2026, 8, 26, 9, 0));
    }
}
