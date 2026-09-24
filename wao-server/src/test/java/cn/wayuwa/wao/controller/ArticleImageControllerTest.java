package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.dto.ArticleImageRequest;
import cn.wayuwa.wao.service.ArticleImageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ArticleImageControllerTest {
    @Test
    void anonymousVisitorCannotCreateOrReadAnImageJob() {
        ArticleImageService service = mock(ArticleImageService.class);
        CurrentMember member = mock(CurrentMember.class);
        MockHttpServletRequest http = new MockHttpServletRequest();
        when(member.id(http)).thenReturn(null);
        ArticleImageController controller = new ArticleImageController(service, member);

        assertThatThrownBy(() -> controller.start(new ArticleImageRequest("正文", "提示词"), http))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.get("ac1", http))
                .isInstanceOf(UnauthorizedException.class);
        verify(service, never()).start(any(), any());
        verify(service, never()).get(any(), any());
    }
}
