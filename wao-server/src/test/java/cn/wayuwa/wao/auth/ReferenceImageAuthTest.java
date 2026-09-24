package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReferenceImageAuthTest {
    @Test
    void privateReferenceRawRequiresSessionWhileExistingPublicImageRemainsReadable() {
        var sessions = mock(AuthSessionManager.class);
        var interceptor = new AuthInterceptor(new AuthProperties(true, false), sessions);
        var privateRequest = new MockHttpServletRequest("GET", "/api/reference-images/abc/raw");
        when(sessions.currentAccountId(privateRequest)).thenReturn(null);
        assertThatThrownBy(() -> interceptor.preHandle(privateRequest, new MockHttpServletResponse(), null))
                .isInstanceOf(UnauthorizedException.class);

        var publicRequest = new MockHttpServletRequest("GET", "/api/images/abc/raw");
        assertThat(interceptor.preHandle(publicRequest, new MockHttpServletResponse(), null)).isTrue();
    }
}
