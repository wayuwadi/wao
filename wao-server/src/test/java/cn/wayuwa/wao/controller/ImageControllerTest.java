package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.service.ImageService;
import cn.wayuwa.wao.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ImageControllerTest {
    private final ImageService images = mock(ImageService.class);
    private final ImageController controller = new ImageController(images, mock(CurrentMember.class), mock(ProjectService.class));

    @Test
    void privateOriginalAndPreviewKeepThePrivacyGuardsNoStorePolicy() {
        var request = new MockHttpServletRequest();
        request.setAttribute("wao.private-note-asset", true);
        var image = new ImageBlobEntity(); image.setContentType("image/png"); image.setContent(new byte[]{1});
        when(images.raw("image")).thenReturn(image);
        when(images.preview("image")).thenReturn(new ImageService.Preview("image/png", new byte[]{2}));

        for (var response : java.util.List.of(controller.raw("image", request), controller.preview("image", request))) {
            assertThat(response.getHeaders().getCacheControl()).contains("no-store", "private").doesNotContain("public");
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                    .isEqualTo("sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:");
        }
    }

    @Test
    void publicThumbnailRetainsShortPublicCaching() {
        when(images.preview("image")).thenReturn(new ImageService.Preview("image/jpeg", new byte[]{2}));
        var response = controller.preview("image", new MockHttpServletRequest());
        assertThat(response.getHeaders().getCacheControl()).contains("public", "max-age=3600");
    }

    @Test
    void svgOriginalAndPreviewAreSandboxedWithoutChangingTheUploadedContent() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><style>rect{fill:red}</style><rect width=\"10\" height=\"10\"/></svg>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var original = new ImageBlobEntity(); original.setContentType("image/svg+xml"); original.setContent(svg);
        when(images.raw("vector")).thenReturn(original);
        when(images.preview("vector")).thenReturn(new ImageService.Preview("image/svg+xml", svg));
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        for (String variant : java.util.List.of("raw", "preview")) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/images/vector/" + variant))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(svg))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("X-Content-Type-Options", "nosniff"));
        }
    }
}
