package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.AgyQuotaAccountDto;
import cn.wayuwa.wao.dto.AgyQuotaBucketDto;
import cn.wayuwa.wao.dto.AgyQuotaGroupDto;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.service.AgyQuotaService;
import cn.wayuwa.wao.service.WorkspaceCapabilityPolicy;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService.AuthenticatedToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgyQuotaControllerTest {
    private final AgyQuotaService service = mock(AgyQuotaService.class);
    private final CurrentMember current = mock(CurrentMember.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final WorkspaceMcpTokenService tokens = mock(WorkspaceMcpTokenService.class);
    private final AgyQuotaController controller = new AgyQuotaController(service, current, members, tokens);

    private AgyQuotaController.ReportRequest request() {
        return new AgyQuotaController.ReportRequest(
                "mac-pro",
                "Agy",
                List.of(new AgyQuotaGroupDto("Gemini Models",
                        List.of(new AgyQuotaBucketDto("gemini-weekly", "Weekly Limit Remaining", "weekly", 79, 1_900_000_000L)))));
    }

    @Test
    void listRequiresLoggedInSession() {
        assertThatThrownBy(() -> controller.list(mock(HttpServletRequest.class)))
                .isInstanceOf(UnauthorizedException.class);
        when(current.id(any())).thenReturn("m1");
        when(service.listAccounts()).thenReturn(List.of());
        assertThat(controller.list(mock(HttpServletRequest.class)).getBody().data()).isEmpty();
    }

    @Test
    void reportAllowsExplicitCliGrantAndRejectsOldOrMissingGrant() {
        var policy = new WorkspaceCapabilityPolicy(List.of("report_agy_quota"), null, null);
        when(tokens.authenticate("valid-token"))
                .thenReturn(new AuthenticatedToken("t1", "m1", true, false, policy));
        when(service.report(eq("mac-pro"), any(), any()))
                .thenReturn(new AgyQuotaAccountDto("agy", "Agy", request().groups(), List.of("mac-pro"), "2026-11-25T03:00:00Z", true));

        var response = controller.report("Bearer valid-token", request(), mock(HttpServletRequest.class));
        assertThat(response.getBody().data().accountLabel()).isEqualTo("Agy");
        verify(service).report(eq("mac-pro"), any(), any());

        var legacy = new AuthenticatedToken("old", "m1", true);
        when(tokens.authenticate("old-token")).thenReturn(legacy);
        assertThatThrownBy(() -> controller.report("Bearer old-token", request(), mock(HttpServletRequest.class)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void reportRejectsNonAdminBrowserSessionAndAllowsAdminSession() {
        var http = mock(HttpServletRequest.class);
        when(current.id(http)).thenReturn("m2");
        var normal = new MemberEntity();
        normal.setId("m2");
        normal.setRole("成员");
        when(members.selectById("m2")).thenReturn(normal);
        assertThatThrownBy(() -> controller.report(null, request(), http)).isInstanceOf(ForbiddenException.class);

        var admin = new MemberEntity();
        admin.setId("m1");
        admin.setRole("管理员");
        when(current.id(http)).thenReturn("m1");
        when(members.selectById("m1")).thenReturn(admin);
        when(service.report(eq("mac-pro"), any(), any()))
                .thenReturn(new AgyQuotaAccountDto("agy", "Agy", request().groups(), List.of("mac-pro"), "2026-11-25T03:00:00Z", true));
        assertThat(controller.report(null, request(), http).getBody().data().accountLabel()).isEqualTo("Agy");
    }
}
