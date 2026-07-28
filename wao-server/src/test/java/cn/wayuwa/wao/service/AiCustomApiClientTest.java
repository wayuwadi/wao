package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiCustomApiClientTest {

    @Test
    void normalizesApiRootToV1() {
        AiCustomApiClient client = new AiCustomApiClient(RestClient.builder());

        assertThat(client.normalizeBaseUrl("https://proxy.example/")).isEqualTo("https://proxy.example/v1");
        assertThat(client.normalizeBaseUrl("https://proxy.example/v1/")).isEqualTo("https://proxy.example/v1");
        assertThatThrownBy(() -> client.normalizeBaseUrl("file:///tmp/api"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void modelsChecksBearerAuthenticationAndParsesOpenAiResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://proxy.example/v1/models"))
                .andExpect(header(AUTHORIZATION, "Bearer sk-test"))
                .andRespond(withSuccess("""
                        {"object":"list","data":[{"id":"gpt-5.4"},{"id":"gpt-5.3-codex"}]}
                        """, MediaType.APPLICATION_JSON));

        var models = new AiCustomApiClient(builder).models("https://proxy.example", "sk-test");

        assertThat(models).containsExactly("gpt-5.3-codex", "gpt-5.4");
        server.verify();
    }
}
