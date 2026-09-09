package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import cn.wayuwa.wao.auth.FeishuLoginProperties;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.FeishuReactionGrantEntity;
import cn.wayuwa.wao.mapper.FeishuReactionGrantMapper;
import java.net.URI;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class FeishuReactionAuthorizationServiceTest {
    private final SettingService settings = mock(SettingService.class);
    private final FeishuReactionGrantMapper grants = mock(FeishuReactionGrantMapper.class);
    private final VaultCryptoService crypto = new VaultCryptoService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FeishuReactionAuthorizationService service = new FeishuReactionAuthorizationService(settings,
            new FeishuLoginProperties(true, "different-login-app", "unused", "https://wao.test/api/auth/feishu/callback", "https://wao.test", "https://feishu.test"),
            grants, crypto, builder, "https://feishu.test/token");
    @BeforeEach void setup() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("bot-app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("bot-secret");
    }
    private String state(MockHttpSession session) { service.authorizationUrl(session); return (String) session.getAttribute("feishu.reaction.state"); }
    private String tokens(String access, String refresh) {
        return """
            {"code":0,"error":"","access_token":"%s","expires_in":7200,"refresh_token":"%s","refresh_token_expires_in":604800,
             "scope":"im:message.reactions:write_only offline_access","token_type":"Bearer"}
            """.formatted(access, refresh);
    }
    @Test void authorizationUsesBotAppMinimumScopesSessionStateAndPkce() {
        var session = new MockHttpSession();
        var uri = UriComponentsBuilder.fromUri(URI.create(service.authorizationUrl(session))).build();
        assertThat(uri.getHost()).isEqualTo("accounts.feishu.cn");
        assertThat(uri.getQueryParams().getFirst("client_id")).isEqualTo("bot-app");
        assertThat(uri.getQueryParams().getFirst("scope")).contains("reactions", "offline_access").doesNotContain("send_as_user");
        assertThat(uri.getQueryParams().getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(uri.getQueryParams().getFirst("code_challenge")).hasSize(43);
        assertThat(session.getAttribute("feishu.reaction.state")).isNotNull();
        verifyNoInteractions(grants);
    }
    @Test void rejectsWrongBrowserExpiredAndReplayedStateBeforeTokenExchange() {
        var session = new MockHttpSession(); var state = state(session);
        assertThatThrownBy(() -> service.complete(new MockHttpSession(), state, "code")).isInstanceOf(IllegalStateException.class);
        session.setAttribute("feishu.reaction.expiry", 1L);
        assertThatThrownBy(() -> service.complete(session, state, "code")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(grants); server.verify();
    }
    @Test void savesOnlyVerifiedOpenIdAndEncryptedTokensWithoutChangingLoginIdentity() {
        var session = new MockHttpSession(); var state = state(session);
        server.expect(requestTo("https://feishu.test/token"))
                .andExpect(content().json("{\"client_id\":\"bot-app\",\"client_secret\":\"bot-secret\",\"code\":\"one-time\",\"grant_type\":\"authorization_code\"}"))
                .andRespond(withSuccess(tokens("personal-access", "personal-refresh"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://feishu.test/open-apis/authen/v1/user_info"))
                .andExpect(header("Authorization", "Bearer personal-access"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"open_id\":\"ou_real\",\"name\":\"娃哥\"}}", MediaType.APPLICATION_JSON));
        assertThat(service.complete(session, state, "one-time")).isEqualTo("娃哥");
        var captured = ArgumentCaptor.forClass(FeishuReactionGrantEntity.class); verify(grants).saveGrant(captured.capture());
        var row = captured.getValue();
        assertThat(row.getOpenId()).isEqualTo("ou_real"); assertThat(row.getAppId()).isEqualTo("bot-app");
        assertThat(crypto.decrypt(row.getAccessCipher(), row.getAccessNonce())).isEqualTo("personal-access");
        assertThat(crypto.decrypt(row.getRefreshCipher(), row.getRefreshNonce())).isEqualTo("personal-refresh");
        assertThat(new String(row.getAccessCipher(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("personal-access");
        assertThatThrownBy(() -> service.complete(session, state, "one-time")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
    @Test void declinedConsentAndMissingReactionScopeNeverPersistGrant() {
        var session = new MockHttpSession(); var state = state(session);
        assertThatThrownBy(() -> service.complete(session, state, null)).hasMessageContaining("尚未同意");
        final var nextState = state(session);
        server.expect(requestTo("https://feishu.test/token")).andRespond(withSuccess(
                "{\"code\":0,\"access_token\":\"private-token\",\"expires_in\":7200,\"scope\":\"offline_access\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.complete(session, nextState, "code")).hasMessageNotContaining("private-token");
        verifyNoInteractions(grants); server.verify();
    }
    @Test void existingGrantIsIsolatedByApplicationAndUser() {
        assertThat(service.accessToken("ou_unknown")).isNull();
        var row = grant(); row.setOpenId("ou_other");
        when(grants.lockById(anyString())).thenReturn(row);
        assertThat(service.accessToken("ou_real")).isNull();
        row.setOpenId("ou_real"); row.setAppId("another-app");
        assertThat(service.accessToken("ou_real")).isNull();
        server.verify();
    }
    private FeishuReactionGrantEntity grant() {
        var row = new FeishuReactionGrantEntity(); row.setId(FeishuReactionAuthorizationService.key("bot-app", "ou_real"));
        row.setAppId("bot-app"); row.setOpenId("ou_real"); row.setDisplayName("娃哥");
        var a = crypto.encrypt("old-access"); row.setAccessCipher(a.ciphertext()); row.setAccessNonce(a.nonce());
        var r = crypto.encrypt("old-refresh"); row.setRefreshCipher(r.ciphertext()); row.setRefreshNonce(r.nonce());
        row.setExpiresAt(LocalDateTime.now().minusMinutes(1)); row.setRefreshExpiresAt(LocalDateTime.now().plusDays(1));
        return row;
    }
    @Test void refreshRotatesEncryptedCredentialsUnderRowLock() {
        var row = grant(); when(grants.lockById(row.getId())).thenReturn(row);
        server.expect(requestTo("https://feishu.test/token"))
                .andExpect(content().json("{\"grant_type\":\"refresh_token\",\"refresh_token\":\"old-refresh\",\"client_id\":\"bot-app\"}"))
                .andRespond(withSuccess(tokens("new-access", "new-refresh"), MediaType.APPLICATION_JSON));
        assertThat(service.accessToken("ou_real")).isEqualTo("new-access");
        verify(grants).saveGrant(row);
        assertThat(crypto.decrypt(row.getRefreshCipher(), row.getRefreshNonce())).isEqualTo("new-refresh");
        assertThat(service.accessToken("ou_real")).isEqualTo("new-access");
        server.verify();
    }
    @Test void revokedRefreshReturnsNoTokenRatherThanAppCredentials() {
        var row = grant(); when(grants.lockById(row.getId())).thenReturn(row);
        server.expect(requestTo("https://feishu.test/token")).andRespond(withBadRequest().body("{\"error\":\"invalid_grant\"}"));
        assertThat(service.accessToken("ou_real")).isNull(); verify(grants, never()).saveGrant(any()); server.verify();
    }
    @Test void httpFailureShowsSafeCategoryAndNeverLeaksResponseOrThrowable() {
        var session = new MockHttpSession(); var state = state(session);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(FeishuReactionAuthorizationService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            server.expect(requestTo("https://feishu.test/token")).andRespond(withBadRequest()
                    .contentType(MediaType.APPLICATION_JSON).body("""
                    {"error":"invalid_client","code":20048,"error_description":"secret-value private-code",
                     "access_token":"private-token","refresh_token":"private-refresh"}
                    """));
            assertThatThrownBy(() -> service.complete(session, state, "private-code"))
                    .hasMessageContaining("应用凭据校验失败").hasMessageContaining("诊断号")
                    .hasMessageNotContaining("private").hasMessageNotContaining("secret-value").hasNoCause();
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage()).contains("reason=invalid_client", "http=400", "code=20048")
                    .doesNotContain("private", "secret-value");
            assertThat(appender.list.get(0).getThrowableProxy()).isNull();
        } finally { logger.detachAppender(appender); appender.stop(); }
        verifyNoInteractions(grants); server.verify();
    }
    @Test void malformedHttpErrorAndNetworkFailuresDoNotBecomePermissionAdvice() {
        var session = new MockHttpSession(); var state = state(session);
        server.expect(requestTo("https://feishu.test/token")).andRespond(withServerError().body("private-response"));
        assertThatThrownBy(() -> service.complete(session, state, "code"))
                .hasMessageContaining("令牌兑换未完成").hasMessageNotContaining("private-response").hasNoCause();
        server.verify(); server.reset();
        var nextState = state(session);
        server.expect(requestTo("https://feishu.test/token")).andRespond(withException(new java.io.IOException("private-code")));
        assertThatThrownBy(() -> service.complete(session, nextState, "code"))
                .hasMessageContaining("暂时无法完成飞书令牌请求").hasMessageNotContaining("private-code").hasNoCause();
        verifyNoInteractions(grants); server.verify();
    }
    @Test void nonemptyErrorOnHttpSuccessStillRejectsCredentials() {
        var session = new MockHttpSession(); var state = state(session);
        server.expect(requestTo("https://feishu.test/token")).andRespond(withSuccess(
                tokens("private-access", "private-refresh").replace("\"error\":\"\"", "\"error\":\"invalid_grant\""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.complete(session, state, "code"))
                .hasMessageContaining("授权码已失效或校验未通过").hasMessageNotContaining("private-access");
        verifyNoInteractions(grants); server.verify();
    }
}
