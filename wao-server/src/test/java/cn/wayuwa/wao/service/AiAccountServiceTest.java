package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AiAccountEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.AiAccountCredentialsDto;
import cn.wayuwa.wao.dto.AiAccountFormRequest;
import cn.wayuwa.wao.dto.AiAccountSyncResultDto;
import cn.wayuwa.wao.mapper.AiAccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.web.client.RestClient;

class AiAccountServiceTest {

    private static final String TEST_KEY = "ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=";

    private final AiAccountMapper mapper = mock(AiAccountMapper.class);
    private final VaultCryptoService crypto = new VaultCryptoService(TEST_KEY);
    private final AiRunService aiRunService = mock(AiRunService.class);
    private final RestClient.Builder restClientBuilder = mock(RestClient.Builder.class);
    private final RestClient restClient = mock(RestClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiAccountService service = new AiAccountService(mapper, crypto, aiRunService,
            restClientBuilder, objectMapper, "");

    private Map<String, AiAccountEntity> backedByMemory() {
        Map<String, AiAccountEntity> rows = new HashMap<>();
        when(mapper.selectById(any())).thenAnswer((InvocationOnMock i) -> rows.get(i.getArgument(0).toString()));
        when(mapper.insert(any(AiAccountEntity.class))).thenAnswer((InvocationOnMock i) -> {
            AiAccountEntity e = i.getArgument(0);
            rows.put(e.getId(), e);
            return 1;
        });
        when(mapper.updateById(any(AiAccountEntity.class))).thenAnswer((InvocationOnMock i) -> {
            AiAccountEntity e = i.getArgument(0);
            rows.put(e.getId(), e);
            return 1;
        });
        when(mapper.deleteById(any(String.class))).thenAnswer((InvocationOnMock i) -> {
            rows.remove(i.getArgument(0).toString());
            return 1;
        });
        when(mapper.selectList(any())).thenAnswer((InvocationOnMock i) -> {
            Object wrapper = i.getArgument(0);
            if (wrapper == null) {
                return List.copyOf(rows.values());
            }
            return rows.values().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getEnabled()))
                    .toList();
        });
        when(restClientBuilder.build()).thenReturn(restClient);
        return rows;
    }

    @Test
    void credentialsAreEncryptedAtRest() {
        backedByMemory();

        service.create(new AiAccountFormRequest("claude", "测试", null, "{\"token\":\"secret\"}", true));

        AiAccountEntity stored = mapper.selectById(mapper.selectList(null).get(0).getId());
        assertThat(stored.getCredentialJson()).startsWith("enc:");
        assertThat(stored.getCredentialJson()).doesNotContain("secret");
    }

    @Test
    void updateWithBlankCredentialsKeepsOldEncryptedValue() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("claude", "测试", null, "{\"token\":\"secret\"}", true));
        String oldCipher = mapper.selectById(created.id()).getCredentialJson();

        service.update(created.id(), new AiAccountFormRequest("claude", "新名称", null, "", true));

        assertThat(mapper.selectById(created.id()).getCredentialJson()).isEqualTo(oldCipher);
    }

    @Test
    void syncCodexReturnsNoApiWithoutFabricatingBalance() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("codex", "Codex", "a@b.com",
                "{\"id_token\":\"eyJhbGciOiJIUzI1NiJ9.eyJlbWFpbCI6ImFAYi5jb20ifQ.fake\"}", true));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("NO_API");
        assertThat(result.message()).contains("a@b.com");
    }

    @Test
    void syncQoderReturnsNoApi() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("qoder", "Qoder", null, "{}", true));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("NO_API");
    }

    @Test
    void enabledCredentialsValidatesRunnerToken() {
        when(aiRunService.authenticateRunner("bad")).thenThrow(new UnauthorizedException("invalid"));

        assertThatThrownBy(() -> service.enabledCredentials("bad"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void enabledCredentialsReturnsPlaintextOnlyForEnabledAccounts() {
        backedByMemory();
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn-1");
        when(aiRunService.authenticateRunner("token")).thenReturn(runner);
        service.create(new AiAccountFormRequest("claude", "A", null, "{\"t\":\"a\"}", true));
        service.create(new AiAccountFormRequest("codex", "B", null, "{\"t\":\"b\"}", false));

        List<AiAccountCredentialsDto> result = service.enabledCredentials("token");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).provider()).isEqualTo("claude");
        assertThat(result.get(0).credentials()).contains("\"t\":\"a\"");
    }

    @Test
    void rejectsInvalidProvider() {
        assertThatThrownBy(() -> service.create(new AiAccountFormRequest("gpt", "X", null, "{}", true)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsBlankCredentialsOnCreate() {
        assertThatThrownBy(() -> service.create(new AiAccountFormRequest("claude", "X", null, "", true)))
                .isInstanceOf(BadRequestException.class);
        verify(mapper, never()).insert(any(AiAccountEntity.class));
    }

    @Test
    void deleteMissingAccountThrowsNotFound() {
        assertThatThrownBy(() -> service.delete("aa-missing"))
                .isInstanceOf(NotFoundException.class);
    }
}
