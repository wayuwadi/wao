package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;
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

    private static OpenAiCodexOAuthClient client(RestClient.Builder builder) {
        return new OpenAiCodexOAuthClient(builder, "client-1",
                "https://auth.openai.test/oauth/authorize",
                "https://auth.openai.test/oauth/token",
                "http://localhost:1455/auth/callback");
    }
}
