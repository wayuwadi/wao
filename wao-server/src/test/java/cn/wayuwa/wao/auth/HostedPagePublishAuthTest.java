package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import cn.wayuwa.wao.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HostedPagePublishAuthTest {
    @Test
    void onlyExactBearerPublishingRoutesBypassTheBrowserSessionCheck() {
        var sessions = mock(AuthSessionManager.class);
        var interceptor = new AuthInterceptor(new AuthProperties(true, false), sessions);
        var response = new MockHttpServletResponse();

        var post = new MockHttpServletRequest("POST", "/api/hosted-pages");
        post.addHeader("Authorization", "Bearer workspace-token");
        assertThat(interceptor.preHandle(post, response, null)).isTrue();

        var preflight = new MockHttpServletRequest("GET", "/api/hosted-pages/publish-access");
        preflight.addHeader("Authorization", "Bearer workspace-token");
        assertThat(interceptor.preHandle(preflight, response, null)).isTrue();

        var uploadWithoutBearer = new MockHttpServletRequest("POST", "/api/hosted-pages");
        assertThatThrownBy(() -> interceptor.preHandle(uploadWithoutBearer, response, null))
                .isInstanceOf(UnauthorizedException.class);

        var preflightWithoutBearer = new MockHttpServletRequest("GET", "/api/hosted-pages/publish-access");
        assertThatThrownBy(() -> interceptor.preHandle(preflightWithoutBearer, response, null))
                .isInstanceOf(UnauthorizedException.class);

        var update = new MockHttpServletRequest("PUT", "/api/hosted-pages");
        update.addHeader("Authorization", "Bearer workspace-token");
        assertThatThrownBy(() -> interceptor.preHandle(update, response, null))
                .isInstanceOf(UnauthorizedException.class);
    }
}
