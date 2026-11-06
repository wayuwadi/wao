package cn.wayuwa.wao.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageAliasSecurityTest {
    @Test void svgShareAliasAndHostedIconAreInertEvenWhenOpenedAsDocuments() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>window.svgExecuted=true</script></svg>".getBytes();
        var shares = mock(NotePublicShareService.class);
        var image = new ImageBlobEntity(); image.setContentType("image/svg+xml"); image.setContent(svg);
        when(shares.image("token", "image")).thenReturn(image);
        var pages = mock(HostedPageService.class);
        var page = new HostedPageEntity(); page.setIconContentType("image/svg+xml"); page.setIconContent(svg);
        when(pages.publicPage("demo")).thenReturn(page);
        var http = MockMvcBuilders.standaloneSetup(
                new NotePublicShareController(shares, mock(CurrentMember.class), mock(ShareService.class), ""),
                new HostedPageController(pages, mock(CurrentMember.class), mock(ProjectService.class), mock(WorkspaceMcpTokenService.class))).build();
        for (String path : java.util.List.of("/api/share/notes/token/images/image/raw", "/api/pages/demo/icon")) {
            http.perform(get(path)).andExpect(status().isOk()).andExpect(content().bytes(svg))
                    .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        http.perform(get("/api/share/notes/token/images/image/raw")).andExpect(header().string("Cache-Control", "no-store"));
        http.perform(get("/api/pages/demo/icon")).andExpect(header().string("Cache-Control", "no-cache"));
        verify(shares, times(2)).image("token", "image");
    }
}
