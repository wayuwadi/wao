package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.service.HostedPageService;
import cn.wayuwa.wao.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

class HostedPageControllerTest {
    @Test void replacementChecksProjectAccessBeforeWritingAndPublicPageDisablesCache() {
        var pages = mock(HostedPageService.class);
        var member = mock(CurrentMember.class);
        var projects = mock(ProjectService.class);
        var controller = new HostedPageController(pages, member, projects);
        var request = new MockHttpServletRequest();
        var html = new MockMultipartFile("html", "new.html", "text/html", "new".getBytes());
        when(member.id(request)).thenReturn("member");
        when(pages.projectId("h1")).thenReturn("p1");
        controller.replace("h1", html, request);
        var order = inOrder(projects, pages);
        order.verify(projects).requireAccess("p1", "member");
        order.verify(pages).replace("h1", html);
        clearInvocations(pages);
        doThrow(new NotFoundException("项目不可见")).when(projects).requireAccess("p1", "member");
        assertThatThrownBy(() -> controller.replace("h1", html, request)).hasMessageContaining("项目不可见");
        verify(pages, never()).replace("h1", html);
        var page = new HostedPageEntity();
        when(pages.publicPage("stable")).thenReturn(page);
        when(pages.render(page)).thenReturn("new".getBytes());
        var response = controller.page("stable");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox").doesNotContain("allow-same-origin");
        assertThat(response.getBody()).isEqualTo("new".getBytes());
    }
}
