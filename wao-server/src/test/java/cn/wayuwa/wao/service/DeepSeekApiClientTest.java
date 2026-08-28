package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import cn.wayuwa.wao.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
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
    void modelGatewayOnlyAcceptsTheAssignedHttpsBasePath() {
        DeepSeekApiClient client = new DeepSeekApiClient(RestClient.builder());

        assertThat(client.normalizeBaseUrl("model_gateway", DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/"))
                .isEqualTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL);
        assertThatThrownBy(() -> client.normalizeBaseUrl(
                "model_gateway", "https://ai.xingshiapp.cn/api/model-credentials/another-proxy/v1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL);
        assertThatThrownBy(() -> client.normalizeBaseUrl(
                "model_gateway", "https://proxy.example/v1"))
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

    @Test
    void completionRetriesTransientFailuresWithinTheSameRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(2), requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"第三次生成成功"}}]}
                        """, MediaType.APPLICATION_JSON));

        DeepSeekApiClient client = new DeepSeekApiClient(builder, ignored -> { });

        assertThat(client.complete("deepseek", null, "token-test", "deepseek-v4-flash",
                "系统规则", "只读材料")).isEqualTo("第三次生成成功");
        server.verify();
    }

    @Test
    void completionOnlyFailsAfterThreeTransientFailures() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(3), requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        DeepSeekApiClient client = new DeepSeekApiClient(builder, ignored -> { });

        assertThatThrownBy(() -> client.complete("deepseek", null, "token-test", "deepseek-v4-flash",
                "系统规则", "只读材料"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("502");
        server.verify();
    }

    @Test
    void completionDoesNotRetryCredentialErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        DeepSeekApiClient client = new DeepSeekApiClient(builder, ignored -> { });

        assertThatThrownBy(() -> client.complete("deepseek", null, "token-test", "deepseek-v4-flash",
                "系统规则", "只读材料"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("401");
        server.verify();
    }

    @Test
    void modelGatewayUsesOpenAiV1RoutesAndBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/models"))
                .andExpect(header(AUTHORIZATION, "Bearer gateway-token"))
                .andRespond(withSuccess("""
                        {"object":"list","data":[{"id":"deepseek-v4-flash"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/chat/completions"))
                .andExpect(header(AUTHORIZATION, "Bearer gateway-token"))
                .andExpect(content().json("""
                        {"model":"deepseek-v4-flash","stream":false}
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"网关润色结果。"}}]}
                        """, MediaType.APPLICATION_JSON));
        DeepSeekApiClient client = new DeepSeekApiClient(builder);

        assertThat(client.models("model_gateway", DeepSeekApiClient.MODEL_GATEWAY_BASE_URL, "gateway-token"))
                .containsExactly("deepseek-v4-flash");
        assertThat(client.polish("model_gateway", DeepSeekApiClient.MODEL_GATEWAY_BASE_URL,
                "gateway-token", "deepseek-v4-flash", "待润色内容。"))
                .isEqualTo("网关润色结果。");
        server.verify();
    }
}
