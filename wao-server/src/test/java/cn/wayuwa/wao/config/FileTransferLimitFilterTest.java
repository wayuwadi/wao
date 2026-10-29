package cn.wayuwa.wao.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileTransferLimitFilterTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void busyTransferRejectsOtherBodiesButLeavesHealthAndMetadataAvailableThenReleasesCapacity() throws Exception {
        var filter = new FileTransferLimitFilter(json, 1);
        var entered = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/files"), entered, (request, response) -> {
            for (String[] endpoint : new String[][] {
                    {"POST", "/api/files"}, {"PUT", "/api/files/example/content"},
                    {"GET", "/api/files/example/download"}, {"GET", "/api/files/example/stream"},
                    {"GET", "/api/files/example/preview"}, {"GET", "/api/files/example/public-preview"},
                    {"HEAD", "/api/files/example/download"}, {"HEAD", "/api/files/example/stream"},
                    {"GET", "/api/%66iles/example/download"}, {"GET", "/api/files;v=1/example/download"}}) {
                var blocked = new MockHttpServletResponse();
                var chain = mock(FilterChain.class);
                filter.doFilter(new MockHttpServletRequest(endpoint[0], endpoint[1]), blocked, chain);
                assertThat(blocked.getStatus()).as(endpoint[1]).isEqualTo(503);
                assertThat(blocked.getHeader("Retry-After")).isEqualTo("2");
                assertThat(json.readTree(blocked.getContentAsString()).path("code").asInt()).isEqualTo(50301);
                verifyNoInteractions(chain);
            }
            for (String path : new String[] {"/actuator/health", "/api/files/example/meta", "/api/tasks"}) {
                var allowed = new MockHttpServletResponse();
                var chain = mock(FilterChain.class);
                var incoming = new MockHttpServletRequest("GET", path);
                filter.doFilter(incoming, allowed, chain);
                verify(chain).doFilter(incoming, allowed);
                assertThat(allowed.getStatus()).isEqualTo(200);
            }
        });
        var incoming = new MockHttpServletRequest("GET", "/api/files/example/download");
        var next = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilter(incoming, next, chain);
        verify(chain).doFilter(incoming, next);
        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test void failedResponseWritingReleasesPermitAndContextPathCannotBypassTheLimit() throws Exception {
        var filter = new FileTransferLimitFilter(json, 1);
        var incoming = new MockHttpServletRequest("GET", "/wao/api/files/example/download");
        incoming.setContextPath("/wao");
        assertThatThrownBy(() -> filter.doFilter(incoming, new MockHttpServletResponse(), (request, response) -> {
            var nested = new MockHttpServletRequest("PUT", "/wao/api/files/example/content");
            nested.setContextPath("/wao");
            var rejected = new MockHttpServletResponse();
            var chain = mock(FilterChain.class);
            filter.doFilter(nested, rejected, chain);
            assertThat(rejected.getStatus()).isEqualTo(503);
            verifyNoInteractions(chain);
            throw new IOException("client disconnected during response");
        })).isInstanceOf(IOException.class).hasMessageContaining("disconnected");
        var next = new MockHttpServletRequest("POST", "/api/files");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilter(next, response, chain);
        verify(chain).doFilter(next, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
