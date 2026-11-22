package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.service.VaultService;
import cn.wayuwa.wao.service.WorkspaceCapabilityPolicy;
import cn.wayuwa.wao.service.WorkspaceMcpTokenService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VaultCliControllerTest {
    private final VaultService vault = mock(VaultService.class);
    private final WorkspaceMcpTokenService tokens = mock(WorkspaceMcpTokenService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new VaultCliController(tokens, vault))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @BeforeEach void grants() {
        when(tokens.authenticate("legacy")).thenReturn(new WorkspaceMcpTokenService.AuthenticatedToken("legacy", "alice", true));
        when(tokens.authenticate("find")).thenReturn(grant("vault_lookup"));
        when(tokens.authenticate("save")).thenReturn(grant("vault_save"));
        when(tokens.authenticate("devops")).thenReturn(grant("vault_devops"));
    }

    @Test void lookupIsScopedToMemberAndReturnsOnlyMetadata() throws Exception {
        when(vault.findForCli("alice", "Dev", null)).thenReturn(List.of(new VaultService.CliItem("vi1", "Dev", true)));
        mvc.perform(post("/api/vault/cli/find").header("Authorization", "Bearer find").contentType("application/json")
                        .content("{\"name\":\"Dev\",\"memberId\":\"bob\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data[0].hasSecret").value(true)).andExpect(jsonPath("$.data[0].secret").doesNotExist());
    }

    @Test void legacyOrLookupGrantsCannotSave() throws Exception {
        for (String token : List.of("legacy", "find")) {
            mvc.perform(post("/api/vault/cli/save").header("Authorization", "Bearer " + token).contentType("application/json")
                            .content("{\"name\":\"Dev\",\"secret\":\"synthetic\"}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/vault/cli/save-access")).andExpect(status().isUnauthorized());
        verifyNoInteractions(vault);
    }

    @Test void savedCredentialIsNeverEchoed() throws Exception {
        when(vault.saveForCli("alice", "Dev", "fixture-user", "synthetic")).thenReturn(
                new VaultService.CliSave(new VaultService.CliItem("vi1", "Dev", true), true));
        mvc.perform(post("/api/vault/cli/save").header("Authorization", "Bearer save").contentType("application/json")
                        .content("{\"name\":\"Dev\",\"account\":\"fixture-user\",\"secret\":\"synthetic\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.saved").value(true))
                .andExpect(jsonPath("$.data.item.secret").doesNotExist()).andExpect(jsonPath("$.data.item.account").doesNotExist());
    }

    @Test void managedDeviceAuthorizationUsesItsOwnGrantAndMember() throws Exception {
        String device = "a".repeat(32);
        for (String token : List.of("legacy", "find", "save")) {
            mvc.perform(get("/api/vault/cli/devops/{device}", device).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        when(vault.devopsEnvelope("alice", device)).thenReturn(new VaultService.DevopsEnvelope("sealed-fixture", "revision"));
        mvc.perform(get("/api/vault/cli/devops/{device}", device).header("Authorization", "Bearer devops"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.envelope").value("sealed-fixture"));
    }

    private static WorkspaceMcpTokenService.AuthenticatedToken grant(String operation) {
        return new WorkspaceMcpTokenService.AuthenticatedToken(operation, "alice", false, false,
                new WorkspaceCapabilityPolicy(List.of(operation), null, null));
    }
}
