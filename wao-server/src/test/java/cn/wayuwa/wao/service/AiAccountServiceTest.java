package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AiAccountEntity;
import cn.wayuwa.wao.domain.AiOauthSessionEntity;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnthropicOAuthClient anthropic = mock(AnthropicOAuthClient.class);
    private final OpenAiCodexOAuthClient codex = mock(OpenAiCodexOAuthClient.class);
    private final AiCustomApiClient customApi = mock(AiCustomApiClient.class);
    private final AiOauthSessionMapper sessionMapper = mock(AiOauthSessionMapper.class);
    private final AiAccountService service = new AiAccountService(mapper, crypto, objectMapper,
            anthropic, codex, customApi, sessionMapper);

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
    void syncCodexReturnsNoApiWhenUsageHasNoWindowWithoutFabricatingBalance() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("codex", "Codex", "a@b.com",
                "{\"tokens\":{\"access_token\":\"access-empty\",\"account_id\":\"acct-empty\"}}", true));
        when(codex.usage("access-empty", "acct-empty")).thenReturn(json("{}"));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("NO_API");
        assertThat(result.message()).contains("未返回可解析的限额窗口");
        assertThat(mapper.selectById(created.id()).getQuotaPercent()).isNull();
    }

    @Test
    void syncQoderReturnsNoApi() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("qoder", "Qoder", null, "{}", true));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("NO_API");
    }

    @Test
    void enabledCredentialsReturnsPlaintextOnlyForEnabledAccounts() {
        backedByMemory();
        service.create(new AiAccountFormRequest("claude", "A", null, "{\"t\":\"a\"}", true));
        service.create(new AiAccountFormRequest("codex", "B", null, "{\"t\":\"b\"}", false));

        List<AiAccountCredentialsDto> result = service.enabledCredentials();

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
        return service.enabledCredentials().get(0).credentials();
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
        assertThatThrownBy(() -> service.startLogin(new StartAiAccountLoginRequest("qoder", null), "m1"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void startLoginUsesCodexDeviceCodeFlow() {
        when(codex.newState()).thenReturn("state-1");
        when(codex.requestDeviceCode()).thenReturn(new OpenAiCodexOAuthClient.DeviceCode(
                "https://auth.openai.com/codex/device", "ABCD-EFGH", "device-1", 5));

        var result = service.startLogin(new StartAiAccountLoginRequest("codex", "工作账号"), "m1");

        assertThat(result.state()).isEqualTo("state-1");
        assertThat(result.authorizeUrl()).isEqualTo("https://auth.openai.com/codex/device");
        assertThat(result.flow()).isEqualTo("device_code");
        assertThat(result.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(result.hint()).doesNotContain("localhost").contains("自动保存");
        verify(sessionMapper).insert(any(AiOauthSessionEntity.class));
    }

    @Test
    void codexDeviceLoginCompletesWithoutLocalhostCallback() {
        backedByMemory();
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState("st-device");
        session.setProvider("codex");
        session.setFlow("device_code");
        session.setDeviceAuthId("device-1");
        session.setUserCode("ABCD-EFGH");
        session.setPollIntervalSeconds(5);
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(sessionMapper.selectById("st-device")).thenReturn(session);
        var poll = OpenAiCodexOAuthClient.DevicePollResult.authorized("code-1", "verifier-1");
        when(codex.pollDeviceCode("device-1", "ABCD-EFGH")).thenReturn(poll);
        String idToken = jwt("""
                {"email":"codex@example.com","https://api.openai.com/auth":{"chatgpt_plan_type":"plus","chatgpt_account_id":"acct-1"}}
                """);
        when(codex.exchangeDeviceCode(poll))
                .thenReturn(new OpenAiCodexOAuthClient.TokenSet(idToken, "access-1", "refresh-1", 3600));
        when(codex.usage("access-1", "acct-1")).thenReturn(json("""
                {"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":35}}}
                """));

        var result = service.loginStatus("st-device");

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.account().email()).isEqualTo("codex@example.com");
        assertThat(storedCredential()).contains("\"access_token\":\"access-1\"");
        verify(sessionMapper).deleteById("st-device");
    }

    @Test
    void codexDeviceLoginReturnsPendingWhileUserHasNotAuthorized() {
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState("st-pending");
        session.setProvider("codex");
        session.setFlow("device_code");
        session.setDeviceAuthId("device-1");
        session.setUserCode("ABCD-EFGH");
        session.setPollIntervalSeconds(5);
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(sessionMapper.selectById("st-pending")).thenReturn(session);
        when(codex.pollDeviceCode("device-1", "ABCD-EFGH"))
                .thenReturn(OpenAiCodexOAuthClient.DevicePollResult.waiting());

        var result = service.loginStatus("st-pending");

        assertThat(result.status()).isEqualTo("pending");
        assertThat(result.account()).isNull();
        verify(sessionMapper).updateById(session);
        verify(mapper, never()).insert(any(AiAccountEntity.class));
    }

    @Test
    void completeCodexLoginStoresOfficialAuthJson() {
        backedByMemory();
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState("st-codex");
        session.setProvider("codex");
        session.setCodeVerifier("verifier");
        session.setLabel("Codex 工作账号");
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(sessionMapper.selectById("st-codex")).thenReturn(session);
        String idToken = jwt("""
                {"email":"codex@example.com","https://api.openai.com/auth":{"chatgpt_plan_type":"plus","chatgpt_account_id":"acct-1"}}
                """);
        when(codex.exchangeCode("http://localhost:1455/auth/callback?code=abc&state=st-codex",
                "verifier", "st-codex"))
                .thenReturn(new OpenAiCodexOAuthClient.TokenSet(idToken, "access-1", "refresh-1", 3600));
        when(codex.usage("access-1", "acct-1")).thenReturn(json("""
                {"plan_type":"plus","rate_limit":{
                  "primary_window":{"used_percent":35,"limit_window_seconds":18000,"reset_at":1785241650},
                  "secondary_window":{"used_percent":63,"limit_window_seconds":604800,"reset_at":1785822360}
                }}
                """));

        var dto = service.completeLogin(new CompleteAiAccountLoginRequest("st-codex",
                "http://localhost:1455/auth/callback?code=abc&state=st-codex"));

        assertThat(dto.provider()).isEqualTo("codex");
        assertThat(dto.authMethod()).isEqualTo("oauth");
        assertThat(dto.email()).isEqualTo("codex@example.com");
        assertThat(dto.plan()).isEqualTo("plus");
        assertThat(dto.quotaStatus()).isEqualTo("SYNCED");
        assertThat(dto.quotaWindow()).isEqualTo("weekly_all");
        assertThat(dto.quotaPercent()).isEqualTo(63);
        assertThat(storedCredential()).contains("\"auth_mode\":\"chatgpt\"")
                .contains("\"access_token\":\"access-1\"")
                .contains("\"refresh_token\":\"refresh-1\"")
                .contains("\"account_id\":\"acct-1\"");
        verify(sessionMapper).deleteById("st-codex");
    }

    @Test
    void codexSyncReadsRealRateLimitWindows() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("codex", "Codex", "codex@example.com", """
                {"auth_mode":"chatgpt","tokens":{"access_token":"access-9","account_id":"acct-9"}}
                """, true));
        when(codex.usage("access-9", "acct-9")).thenReturn(json("""
                {"plan_type":"plus","rate_limit":{
                  "primary_window":{"used_percent":22,"limit_window_seconds":18000,"reset_at":1785241650},
                  "secondary_window":{"used_percent":71,"limit_window_seconds":604800,"reset_at":1785822360}
                }}
                """));

        AiAccountSyncResultDto result = service.sync(created.id());

        AiAccountEntity stored = mapper.selectById(created.id());
        assertThat(result.status()).isEqualTo("SYNCED");
        assertThat(result.message()).contains("七天额度已用 71%");
        assertThat(stored.getPlan()).isEqualTo("plus");
        assertThat(stored.getQuotaWindow()).isEqualTo("weekly_all");
        assertThat(stored.getQuotaPercent()).isEqualTo(71);
        assertThat(stored.getQuotaResetsAt()).isNotNull();
        assertThat(result.rawJson()).contains("primary_window").doesNotContain("access-9");
    }

    @Test
    void codexSyncFailureKeepsLastRealQuotaSnapshot() {
        backedByMemory();
        var created = service.create(new AiAccountFormRequest("codex", "Codex", null,
                "{\"tokens\":{\"access_token\":\"access-stale\",\"account_id\":\"acct-stale\"}}", true));
        when(codex.usage("access-stale", "acct-stale"))
                .thenReturn(json("""
                        {"rate_limit":{"primary_window":{
                          "used_percent":48,"limit_window_seconds":18000,"reset_at":1785241650
                        }}}
                        """))
                .thenThrow(new BadRequestException("额度服务暂时不可用"));
        service.sync(created.id());

        AiAccountSyncResultDto failed = service.sync(created.id());

        AiAccountEntity stored = mapper.selectById(created.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.message()).contains("暂时不可用");
        assertThat(stored.getQuotaPercent()).isEqualTo(48);
        assertThat(stored.getQuotaRawJson()).contains("primary_window");
    }

    @Test
    void customApiKeyIsEncryptedAndOnlyReturnedForSelectedRun() {
        backedByMemory();
        when(customApi.normalizeBaseUrl("https://proxy.example/v1")).thenReturn("https://proxy.example/v1");
        var created = service.create(new AiAccountFormRequest("codex", "CLI Proxy", null, null, true,
                "https://proxy.example/v1", "gpt-5.4", "responses", "sk-secret"));

        AiAccountEntity stored = mapper.selectById(created.id());
        assertThat(stored.getAuthMethod()).isEqualTo("api_key");
        assertThat(stored.getCredentialJson()).startsWith("enc:").doesNotContain("sk-secret");
        var account = service.runAccount(created.id(), "codex");
        assertThat(account.apiKey()).isEqualTo("sk-secret");
        assertThat(account.baseUrl()).isEqualTo("https://proxy.example/v1");
        assertThat(account.model()).isEqualTo("gpt-5.4");
        assertThat(account.credentials()).isNull();
    }

    @Test
    void customApiSyncUsesStoredKeyAndReportsAvailableModels() {
        backedByMemory();
        when(customApi.normalizeBaseUrl("https://proxy.example")).thenReturn("https://proxy.example/v1");
        when(customApi.models("https://proxy.example/v1", "sk-secret"))
                .thenReturn(List.of("gpt-5.3-codex", "gpt-5.4"));
        var created = service.create(new AiAccountFormRequest("codex", "CLI Proxy", null, null, true,
                "https://proxy.example", "gpt-5.4", "responses", "sk-secret"));

        AiAccountSyncResultDto result = service.sync(created.id());

        assertThat(result.status()).isEqualTo("SYNCED");
        assertThat(result.message()).contains("2 个模型");
        assertThat(result.rawJson()).contains("gpt-5.4");
    }

    @Test
    void expiringCodexOauthCredentialIsRefreshedBeforeClaim() {
        Map<String, AiAccountEntity> rows = backedByMemory();
        AiOauthSessionEntity session = new AiOauthSessionEntity();
        session.setState("st-refresh");
        session.setProvider("codex");
        session.setCodeVerifier("verifier");
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(sessionMapper.selectById("st-refresh")).thenReturn(session);
        String oldIdToken = jwt("""
                {"email":"codex@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct-1"}}
                """);
        when(codex.exchangeCode("code", "verifier", "st-refresh"))
                .thenReturn(new OpenAiCodexOAuthClient.TokenSet(oldIdToken, "old-access", "refresh-1", 3600));
        var created = service.completeLogin(new CompleteAiAccountLoginRequest("st-refresh", "code"));
        rows.get(created.id()).setTokenExpiresAt(LocalDateTime.now().minusSeconds(1));
        String newIdToken = jwt("""
                {"email":"codex@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"acct-1"}}
                """);
        when(codex.refresh("refresh-1"))
                .thenReturn(new OpenAiCodexOAuthClient.TokenSet(newIdToken, "new-access", null, 7200));

        service.validateRunAccount(created.id(), "codex");
        verify(codex, never()).refresh("refresh-1");

        var account = service.runAccount(created.id(), "codex");

        assertThat(account.credentials()).contains("\"access_token\":\"new-access\"")
                .contains("\"refresh_token\":\"refresh-1\"");
        verify(codex).refresh("refresh-1");
    }

    private static String jwt(String payload) {
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.trim().getBytes(StandardCharsets.UTF_8)) + ".signature";
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
        long past = System.currentTimeMillis() - 1000;
        service.create(new AiAccountFormRequest("claude", "C", null,
                "{\"claudeAiOauth\":{\"accessToken\":\"stale\",\"refreshToken\":\"rt\",\"expiresAt\":" + past + "}}",
                true));
        when(anthropic.refresh("rt")).thenThrow(new BadRequestException("refresh token 已作废"));

        List<AiAccountCredentialsDto> result = service.enabledCredentials();

        // 一个账号续不了期，不该让 Runner 一份凭据都拿不到。
        assertThat(result).hasSize(1);
        assertThat(result.get(0).credentials()).contains("stale");
    }
}
