package cn.wayuwa.wao.auth;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import cn.wayuwa.wao.common.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
class PetCliAuthTest {
    @Test void onlyExactMethodsAndPetRoutesDelegateToBearerController() {
        var interceptor = new AuthInterceptor(new AuthProperties(true, false), mock(AuthSessionManager.class));
        for (String route : new String[]{"GET /api/pets/cli-access", "GET /api/pets/mine", "POST /api/pets", "PUT /api/pets/p1/visibility"}) {
            var parts=route.split(" "); var request=new MockHttpServletRequest(parts[0],parts[1]);
            request.addHeader("Authorization", "Bearer test-token");
            assertThat(interceptor.preHandle(request,new MockHttpServletResponse(),null)).isTrue();
        }
        for (String route : new String[]{"GET /api/pets/p1/download", "GET /api/pets/p1/sprite", "POST /api/pets/mine", "DELETE /api/pets/p1", "POST /api/workspace-mcp/tokens", "PUT /api/pets/p1/visibility/extra"}) {
            var parts=route.split(" "); var request=new MockHttpServletRequest(parts[0],parts[1]);
            request.addHeader("Authorization", "Bearer test-token");
            assertThatThrownBy(() -> interceptor.preHandle(request,new MockHttpServletResponse(),null)).isInstanceOf(UnauthorizedException.class);
        }
        var request=new MockHttpServletRequest("POST","/api/pets");
        assertThatThrownBy(() -> interceptor.preHandle(request,new MockHttpServletResponse(),null)).isInstanceOf(UnauthorizedException.class);
    }
}
