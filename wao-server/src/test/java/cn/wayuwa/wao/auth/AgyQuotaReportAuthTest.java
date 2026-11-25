package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import cn.wayuwa.wao.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgyQuotaReportAuthTest {
    @Test
    void onlyPostReportWithAuthorizationDelegatesToController() {
        var interceptor = new AuthInterceptor(new AuthProperties(true, false), mock(AuthSessionManager.class));

        var allowed = new MockHttpServletRequest("POST", "/api/agy-quota/report");
        allowed.addHeader("Authorization", "Bearer wao_workspace_test");
        assertThat(interceptor.preHandle(allowed, new MockHttpServletResponse(), null)).isTrue();

        var missingAuth = new MockHttpServletRequest("POST", "/api/agy-quota/report");
        assertThatThrownBy(() -> interceptor.preHandle(missingAuth, new MockHttpServletResponse(), null))
                .isInstanceOf(UnauthorizedException.class);

        var getQuotasWithBearer = new MockHttpServletRequest("GET", "/api/agy-quota");
        getQuotasWithBearer.addHeader("Authorization", "Bearer wao_workspace_test");
        assertThatThrownBy(() -> interceptor.preHandle(getQuotasWithBearer, new MockHttpServletResponse(), null))
                .isInstanceOf(UnauthorizedException.class);
    }
}
