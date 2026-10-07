package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.*;
import cn.wayuwa.wao.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

class PetRepositoryControllerTest {
    private final PetRepositoryService pets = mock(PetRepositoryService.class);
    private final CurrentMember current = mock(CurrentMember.class);
    private final WorkspaceMcpTokenService tokens = mock(WorkspaceMcpTokenService.class);
    private final PetRepositoryController controller = new PetRepositoryController(pets, current, tokens);
    private MockHttpServletRequest request(String secret, List<String> permissions) {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + secret);
        when(tokens.authenticate(secret)).thenReturn(new WorkspaceMcpTokenService.AuthenticatedToken(
                "test-token", "owner", true, false, new WorkspaceCapabilityPolicy(permissions, null, null)));
        return request;
    }

    @Test void uploadPermissionNeverGrantsSharingOrReadingAndAuthenticationPrecedesFileRead() throws Exception {
        var upload = request("upload", List.of("upload_pet"));
        assertThat(controller.cliAccess("upload_pet", upload).getBody().data()).isTrue();
        assertThat(controller.cliAccess("upload_pet", upload).getHeaders().getCacheControl()).isEqualTo("no-store");
        var archive = spy(new MockMultipartFile("archive", "pet.zip", "application/zip", new byte[]{1,2}));
        controller.create(archive, null, null, "original", "MIT", upload);
        verify(pets).create(eq("owner"), any(), isNull(), isNull(), eq("original"), eq("MIT"));
        clearInvocations(pets, archive);
        assertThatThrownBy(() -> controller.mine(upload)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> controller.visibility("other", new PetRepositoryController.Visibility(true, true), upload)).isInstanceOf(ForbiddenException.class);
        var read = request("read", List.of("list_my_pets"));
        assertThatThrownBy(() -> controller.create(archive, null, null, "original", "MIT", read)).isInstanceOf(ForbiddenException.class);
        verify(archive, never()).getBytes();
        verifyNoInteractions(pets);
        controller.mine(read);
        verify(pets).mine("owner");
    }

    @Test void legacyOrOldGranularTokensAndInvalidBearerCannotFallBackToLoggedInBrowser() {
        var old = new MockHttpServletRequest();
        old.addHeader("Authorization", "Bearer old");
        when(current.id(old)).thenReturn("browser-owner");
        when(tokens.authenticate("old")).thenReturn(new WorkspaceMcpTokenService.AuthenticatedToken("legacy", "owner", true, true));
        for (String op : List.of("upload_pet", "share_pet", "list_my_pets"))
            assertThatThrownBy(() -> controller.cliAccess(op, old)).isInstanceOf(ForbiddenException.class);
        var granular = request("old-snapshot", List.of("create_note", "list_notes"));
        assertThatThrownBy(() -> controller.cliAccess("upload_pet", granular)).isInstanceOf(ForbiddenException.class);
        var invalid = new MockHttpServletRequest(); invalid.addHeader("Authorization", "Bearer revoked");
        when(current.id(invalid)).thenReturn("browser-owner");
        assertThatThrownBy(() -> controller.mine(invalid)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.cliAccess("get_project", old)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(pets, current);
    }

    @Test void shareAndUnsharePassOwnerAndRightsToExistingServiceAndBrowserStillWorks() {
        var request = request("share", List.of("share_pet"));
        controller.visibility("own-pet", new PetRepositoryController.Visibility(true, true), request);
        verify(pets).visibility("owner", "own-pet", true, true);
        doThrow(new NotFoundException("宠物不存在或未公开")).when(pets).visibility("owner", "other-pet", true, true);
        assertThatThrownBy(() -> controller.visibility("other-pet", new PetRepositoryController.Visibility(true, true), request)).isInstanceOf(NotFoundException.class);
        controller.visibility("own-pet", new PetRepositoryController.Visibility(false, false), request);
        verify(pets).visibility("owner", "own-pet", false, false);
        var browser = new MockHttpServletRequest(); when(current.id(browser)).thenReturn("browser-owner");
        controller.mine(browser);
        verify(pets).mine("browser-owner");
        assertThatThrownBy(() -> controller.cliAccess("upload_pet", browser)).isInstanceOf(UnauthorizedException.class);
    }
}
