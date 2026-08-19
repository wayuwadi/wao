package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeepSeekApiClientTest {

    @Test
    void onlyAcceptsOfficialDeepSeekApiHost() {
        DeepSeekApiClient client = new DeepSeekApiClient(RestClient.builder());

        assertThat(client.normalizeBaseUrl(null)).isEqualTo("https://api.deepseek.com");
        assertThat(client.normalizeBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com");
        assertThatThrownBy(() -> client.normalizeBaseUrl("https://proxy.example/v1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("只允许发送到");
    }

    @Test
    void listsModelsWithBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/models"))
                .andExpect(header(AUTHORIZATION, "Bearer token-test"))
                .andRespond(withSuccess("""
                        {"object":"list","data":[{"id":"deepseek-v4-pro"},{"id":"deepseek-v4-flash"}]}
                        """, MediaType.APPLICATION_JSON));

        var models = new DeepSeekApiClient(builder).models(null, "token-test");

        assertThat(models).containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
        server.verify();
    }

    @Test
    void polishesWithNonThinkingChatCompletionAndReturnsOnlyContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header(AUTHORIZATION, "Bearer token-test"))
                .andExpect(content().json("""
                        {
                          "model":"deepseek-v4-flash",
                          "messages":[
                            {"role":"system"},
                            {"role":"user","content":"这段话不太顺。"}
                          ],
                          "thinking":{"type":"disabled"},
                          "stream":false
                        }
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"这段话表达得更流畅。"}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = new DeepSeekApiClient(builder).polish(
                null, "token-test", "deepseek-v4-flash", "这段话不太顺。");

        assertThat(result).isEqualTo("这段话表达得更流畅。");
        server.verify();
    }

    @Test
    void rejectsTruncatedResponseInsteadOfOfferingPartialReplacement() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"length","message":{"content":"不完整"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> new DeepSeekApiClient(builder).polish(
                null, "token-test", "deepseek-v4-pro", "很长的原文"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("被截断");
    }
}
