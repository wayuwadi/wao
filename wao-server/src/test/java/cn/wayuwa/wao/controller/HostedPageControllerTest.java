package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.HostedPageEntity;
import cn.wayuwa.wao.dto.HostedPageDto;
import cn.wayuwa.wao.service.HostedPageService;
import cn.wayuwa.wao.service.ProjectService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

class HostedPageControllerTest {
    @Test void replacementChecksProjectAccessBeforeWritingAndPublicPageDisablesCache() {
        var pages = mock(HostedPageService.class);
        var member = mock(CurrentMember.class);
        var projects = mock(ProjectService.class);
        var controller = new HostedPageController(pages, member, projects, mock(WorkspaceMcpTokenService.class));
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


    @Test void workspaceBearerNeedsDedicatedScopeAndVisibleProjectForHtmlPublishing() {
        var pages = mock(HostedPageService.class);
        var member = mock(CurrentMember.class);
        var projects = mock(ProjectService.class);
        var workspaceTokens = mock(WorkspaceMcpTokenService.class);
        var controller = new HostedPageController(pages, member, projects, workspaceTokens);
        var request = new MockHttpServletRequest();
        var html = new MockMultipartFile("html", "demo.html", "text/html", "<html>demo</html>".getBytes());
        var scoped = new WorkspaceMcpTokenService.AuthenticatedToken("t1", "member", false, true);
        when(workspaceTokens.authenticate("publish-token")).thenReturn(scoped);

        assertThat(controller.publishAccess("Bearer publish-token", "p1").data()).isTrue();
        verify(projects).requireAccess("p1", "member");
        var expected = new HostedPageDto("page-1", "demo-page", "Demo", "demo.html",
                "MANUAL_UPLOAD", false, "now", "/api/pages/demo-page", "p1");
        when(pages.create(html, "demo-page", "Demo", null, "member", "p1")).thenReturn(expected);
        assertThat(controller.create(html, "demo-page", "Demo", null, "p1",
                "Bearer publish-token", request).data()).isEqualTo(expected);
        clearInvocations(pages);

        when(workspaceTokens.authenticate("read-token"))
                .thenReturn(new WorkspaceMcpTokenService.AuthenticatedToken("t2", "member", false, false));
        assertThatThrownBy(() -> controller.publishAccess("Bearer read-token", "p1"))
                .hasMessageContaining("未获 HTML 页面发布权限");
        assertThatThrownBy(() -> controller.create(html, "read-page", "Read", null, null,
                "Bearer read-token", request)).hasMessageContaining("未获 HTML 页面发布权限");
        verify(pages, never()).create(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> controller.publishAccess("invalid", null))
                .hasMessageContaining("令牌无效");

        doThrow(new NotFoundException("项目不可见")).when(projects).requireAccess("private", "member");
        assertThatThrownBy(() -> controller.publishAccess("Bearer publish-token", "private"))
                .hasMessageContaining("项目不可见");
        verify(pages, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test void browserSessionCanStillCreateHtmlWithoutWorkspaceBearer() {
        var pages = mock(HostedPageService.class);
        var member = mock(CurrentMember.class);
        var request = new MockHttpServletRequest();
        when(member.id(request)).thenReturn("browser-member");
        var expected = new HostedPageDto("page-2", "browser-page", "Browser", "page.html",
                "MANUAL_UPLOAD", false, "now", "/api/pages/browser-page", null);
        when(pages.create(any(), any(), any(), any(), any(), any())).thenReturn(expected);
        var controller = new HostedPageController(pages, member, mock(ProjectService.class),
                mock(WorkspaceMcpTokenService.class));
        assertThat(controller.create(new MockMultipartFile("html", "page.html", "text/html", "x".getBytes()),
                "browser-page", "Browser", null, null, null, request).data()).isEqualTo(expected);
        verify(pages).create(any(), any(), any(), any(), eq("browser-member"), isNull());
    }

    @Test void manualPageUsesTrustedShellWithoutSandboxResponseHeader() {
        var pages = mock(HostedPageService.class);
        var controller = new HostedPageController(pages, mock(CurrentMember.class), mock(ProjectService.class), mock(WorkspaceMcpTokenService.class));
        var page = new HostedPageEntity();
        when(pages.publicPage("demo")).thenReturn(page);
        when(pages.isManualPage(page)).thenReturn(true);
        when(pages.renderInteractive(page)).thenReturn("trusted shell".getBytes());

        var response = controller.page("demo");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("frame-ancestors 'self'");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo("trusted shell".getBytes());
        verify(pages, never()).render(page);
    }
}
