package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import cn.wayuwa.wao.common.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCodexOAuthClientTest {

    @Test
    void authorizeUrlUsesOfficialPkceParameters() {
        OpenAiCodexOAuthClient client = client(RestClient.builder());

        String url = client.authorizeUrl("state-1", "verifier-1");

        assertThat(url).startsWith("https://auth.openai.test/oauth/authorize?")
                .contains("client_id=client-1")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback")
                .contains("state=state-1")
                .contains("code_challenge_method=S256")
                .contains("codex_cli_simplified_flow=true")
                .contains("originator=codex_cli_rs")
                .contains("api.connectors.read");
    }

    @Test
    void exchangeAcceptsFullLocalhostCallbackUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://auth.openai.test/oauth/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"id_token":"id-1","access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}
                        """, MediaType.APPLICATION_JSON));

        var tokens = client(builder).exchangeCode(
                "http://localhost:1455/auth/callback?code=code%2B1&state=state-1", "verifier-1", "state-1");

        assertThat(tokens.accessToken()).isEqualTo("access-1");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-1");
        assertThat(tokens.expiresInSeconds()).isEqualTo(3600);
        server.verify();
    }

    @Test
    void exchangeRejectsCallbackFromAnotherLogin() {
        assertThatThrownBy(() -> client(RestClient.builder()).exchangeCode(
                "http://localhost:1455/auth/callback?code=code-1&state=wrong", "verifier-1", "state-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("state");
    }

    @Test
    void deviceCodeFlowUsesOfficialEndpointsAndDeviceCallback() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://auth.openai.test/api/accounts/deviceauth/usercode"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"device_auth_id":"device-1","user_code":"ABCD-EFGH","interval":"5"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth.openai.test/api/accounts/deviceauth/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"authorization_code":"code-1","code_challenge":"challenge-1","code_verifier":"verifier-1"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://auth.openai.test/oauth/token"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"id_token":"id-1","access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}
                        """, MediaType.APPLICATION_JSON));

        OpenAiCodexOAuthClient client = client(builder);
        var device = client.requestDeviceCode();
        var poll = client.pollDeviceCode(device.deviceAuthId(), device.userCode());
        var tokens = client.exchangeDeviceCode(poll);

        assertThat(device.verificationUrl()).isEqualTo("https://auth.openai.test/codex/device");
        assertThat(device.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(device.intervalSeconds()).isEqualTo(5);
        assertThat(poll.authorized()).isTrue();
        assertThat(tokens.accessToken()).isEqualTo("access-1");
        server.verify();
    }

    @Test
    void deviceCodePollTreatsForbiddenAsPending() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://auth.openai.test/api/accounts/deviceauth/token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(client(builder).pollDeviceCode("device-1", "ABCD-EFGH").authorized()).isFalse();
        server.verify();
    }

    @Test
    void usageSendsOauthTokenAndChatGptAccountId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://chatgpt.test/backend-api/wham/usage"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer access-1"))
                .andExpect(header("ChatGPT-Account-Id", "acct-1"))
                .andExpect(header("User-Agent", "WAO"))
                .andRespond(withSuccess("""
                        {"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":63}}}
                        """, MediaType.APPLICATION_JSON));

        JsonNode usage = client(builder).usage("access-1", "acct-1");

        assertThat(usage.path("plan_type").asText()).isEqualTo("plus");
        assertThat(usage.at("/rate_limit/primary_window/used_percent").asInt()).isEqualTo(63);
        server.verify();
    }

    @Test
    void acceptsInternalHttpProxyUrl() {
        OpenAiCodexOAuthClient client = client(RestClient.builder(),
                "http://host.docker.internal:18888");

        assertThat(client).isNotNull();
    }

    @Test
    void rejectsProxyUrlWithUnsupportedProtocolOrCredentials() {
        assertThatThrownBy(() -> client(RestClient.builder(), "socks5://127.0.0.1:18888"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http URL");
        assertThatThrownBy(() -> client(RestClient.builder(), "http://user:pass@127.0.0.1:18888"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http URL");
    }

    private static OpenAiCodexOAuthClient client(RestClient.Builder builder) {
        return client(builder, "");
    }

    private static OpenAiCodexOAuthClient client(RestClient.Builder builder, String proxyUrl) {
        return new OpenAiCodexOAuthClient(builder, "client-1",
                "https://auth.openai.test/oauth/authorize",
                "https://auth.openai.test/oauth/token",
                "http://localhost:1455/auth/callback",
                "https://chatgpt.test/backend-api/wham/usage",
                "https://auth.openai.test",
                proxyUrl);
    }
}
