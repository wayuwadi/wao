package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AiAccountEntity;
import cn.wayuwa.wao.domain.AiOauthSessionEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.AiAccountCredentialsDto;
import cn.wayuwa.wao.dto.AiAccountFormRequest;
import cn.wayuwa.wao.dto.AiAccountSyncResultDto;
import cn.wayuwa.wao.dto.CompleteAiAccountLoginRequest;
import cn.wayuwa.wao.dto.StartAiAccountLoginRequest;
import cn.wayuwa.wao.mapper.AiAccountMapper;
import cn.wayuwa.wao.mapper.AiOauthSessionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

class AiAccountServiceTest {

    private static final String TEST_KEY = "ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=";

    private final AiAccountMapper mapper = mock(AiAccountMapper.class);
    private final VaultCryptoService crypto = new VaultCryptoService(TEST_KEY);
    private final AiRunService aiRunService = mock(AiRunService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicOAuthClient anthropic = mock(AnthropicOAuthClient.class);
    private final AiOauthSessionMapper sessionMapper = mock(AiOauthSessionMapper.class);
    private final AiAccountService service = new AiAccountService(mapper, crypto, aiRunService,
            objectMapper, anthropic, sessionMapper);

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

    /** 通过 Runner 下发接口读回明文凭据——这正是凭据最终要走的那条路。 */
    private String storedCredential() {
        AiRunnerNodeEntity reader = new AiRunnerNodeEntity();
        reader.setId("rn-read");
        when(aiRunService.authenticateRunner("read-token")).thenReturn(reader);
        return service.enabledCredentials("read-token").get(0).credentials();
    }

    private JsonNode json(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final String PROFILE = """
            {"account":{"email":"a@b.com","has_claude_pro":true,"has_claude_max":false}}""";

    @Test
    void startLoginRejectsProvidersWithoutOauthSupport() {
        assertThatThrownBy(() -> service.startLogin(new StartAiAccountLoginRequest("codex", null), "m1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void completeLoginStoresCredentialInClaudeCodeFormat() {
        backedByMemory();
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState("st-1");
        session.setProvider("claude");
        session.setCodeVerifier("verifier");
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(sessionMapper.selectById("st-1")).thenReturn(session);
        when(anthropic.exchangeCode("abc", "verifier", "st-1"))
                .thenReturn(new AnthropicOAuthClient.TokenSet("at-1", "rt-1", 3600, "user:inference user:profile"));
        when(anthropic.profile("at-1")).thenReturn(json(PROFILE));
        when(anthropic.usage("at-1")).thenReturn(json(
                "{\"limits\":[{\"kind\":\"session\",\"percent\":30},{\"kind\":\"weekly_all\",\"percent\":92}]}"));

        var dto = service.completeLogin(new CompleteAiAccountLoginRequest("st-1", "abc"));

        assertThat(dto.authMethod()).isEqualTo("oauth");
        assertThat(dto.email()).isEqualTo("a@b.com");
        assertThat(dto.plan()).isEqualTo("pro");
        // 展示的是最紧张的那条限额，不是第一条。
        assertThat(dto.quotaPercent()).isEqualTo(92);
        assertThat(dto.quotaWindow()).isEqualTo("weekly_all");
        assertThat(dto.quotaStatus()).isEqualTo("SYNCED");
        // Runner 会把这份 JSON 原样写进 ~/.claude/.credentials.json，字段名必须对得上。
        assertThat(storedCredential()).contains("\"claudeAiOauth\"").contains("\"accessToken\":\"at-1\"")
                .contains("\"refreshToken\":\"rt-1\"").contains("\"subscriptionType\":\"pro\"");
        verify(sessionMapper).deleteById("st-1");
    }

    @Test
    void completeLoginRejectsExpiredSession() {
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState("st-old");
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(sessionMapper.selectById("st-old")).thenReturn(session);

        assertThatThrownBy(() -> service.completeLogin(new CompleteAiAccountLoginRequest("st-old", "abc")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void syncMarksNoApiWhenAnthropicReturnsNoParsableWindow() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("claude", "C", null,
                "{\"claudeAiOauth\":{\"accessToken\":\"at-9\"}}", true));
        when(anthropic.profile("at-9")).thenReturn(json(PROFILE));
        when(anthropic.usage("at-9")).thenReturn(json("{\"limits\":[]}"));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("NO_API");
        assertThat(mapper.selectById(created.id()).getQuotaPercent()).isNull();
    }

    @Test
    void expiringTokenIsRefreshedBeforeUse() {
        backedByMemory();
        long past = System.currentTimeMillis() + 10_000; // 10 秒后过期，落在 5 分钟续期窗口内
        var created = service.create(new AiAccountFormRequest("claude", "C", null,
                "{\"claudeAiOauth\":{\"accessToken\":\"old\",\"refreshToken\":\"rt\",\"expiresAt\":" + past + "}}",
                true));
        when(anthropic.refresh("rt"))
                .thenReturn(new AnthropicOAuthClient.TokenSet("new-at", null, 3600, "user:inference"));
        when(anthropic.profile("new-at")).thenReturn(json(PROFILE));
        when(anthropic.usage("new-at")).thenReturn(json("{\"limits\":[{\"kind\":\"session\",\"percent\":5}]}"));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("SYNCED");
        verify(anthropic).refresh("rt");
        // Anthropic 这次没换新的 refresh token，旧的必须留着，否则下次就续不了期了。
        assertThat(storedCredential())
                .contains("\"accessToken\":\"new-at\"").contains("\"refreshToken\":\"rt\"");
    }

    @Test
    void runnerStillGetsCredentialWhenRefreshFails() {
        backedByMemory();
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn-1");
        when(aiRunService.authenticateRunner("token")).thenReturn(runner);
        long past = System.currentTimeMillis() - 1000;
        service.create(new AiAccountFormRequest("claude", "C", null,
                "{\"claudeAiOauth\":{\"accessToken\":\"stale\",\"refreshToken\":\"rt\",\"expiresAt\":" + past + "}}",
                true));
        when(anthropic.refresh("rt")).thenThrow(new BadRequestException("refresh token 已作废"));

        List<AiAccountCredentialsDto> result = service.enabledCredentials("token");

        // 一个账号续不了期，不该让 Runner 一份凭据都拿不到。
        assertThat(result).hasSize(1);
        assertThat(result.get(0).credentials()).contains("stale");
    }
}
