package cn.wayuwa.wao.auth;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.service.NoteAssetPrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotePrivacyAuthTest {
    @Test void privateImageAndFileAliasesRequireSessionEvenWithDownloadTicket() {
        var sessions=mock(AuthSessionManager.class);var assets=mock(NoteAssetPrivacyService.class);
        when(assets.isPrivate("images","secret")).thenReturn(true);
        when(assets.isPrivate("files","secret")).thenReturn(true);
        var guard=new AuthInterceptor(new AuthProperties(true,false),sessions,assets);
        for(String path:new String[]{"/api/images/secret/raw","/api/images/secret/preview","/api/images/%73ecret/raw","/api/images/secret;ignored/raw","/api/files/secret/meta","/api/files/secret/public-preview","/api/files/secret/download","/api/files/secret/challenge","/api/files/secret/ticket"}) {
            var request=new MockHttpServletRequest(path.endsWith("challenge")||path.endsWith("ticket")?"POST":"GET",path);
            request.addParameter("ticket","previously-issued");var response=new MockHttpServletResponse();
            assertThatThrownBy(()->guard.preHandle(request,response,null)).isInstanceOf(NotFoundException.class);
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            when(sessions.currentAccountId(request)).thenReturn("real-account");
            assertThat(guard.preHandle(request,response,null)).isTrue();
            assertThat(request.getAttribute("wao.private-note-asset")).isEqualTo(true);
        }
    }
    @Test void publicShareReadsHaveNoCacheAndMutationsAreNeverPublic() {
        var guard=new AuthInterceptor(new AuthProperties(true,false),mock(AuthSessionManager.class),mock(NoteAssetPrivacyService.class));
        var response=new MockHttpServletResponse();
        assertThat(guard.preHandle(new MockHttpServletRequest("GET","/api/share/notes/ns_invalid"),response,null)).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Robots-Tag")).contains("noindex");
        assertThatThrownBy(()->guard.preHandle(new MockHttpServletRequest("POST","/api/share/notes/ns_invalid"),response,null)).isInstanceOf(cn.wayuwa.wao.common.UnauthorizedException.class);
    }
}
