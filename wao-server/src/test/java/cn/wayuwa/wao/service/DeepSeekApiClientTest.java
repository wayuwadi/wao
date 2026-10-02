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
    private final DeepSeekBudgetService budget = org.mockito.Mockito.mock(DeepSeekBudgetService.class);

    @Test
    void connectionBreakAfterPartialStreamFailsWithoutAutomaticPaidRetry() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        byte[] partial = "data: {\"choices\":[{\"delta\":{\"content\":\"半篇\"}}]}\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var stream = new java.io.ByteArrayInputStream(partial) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (available() == 0) throw new org.springframework.web.client.ResourceAccessException("合成断网");
                return super.read(bytes, offset, length);
            }
        };
        server.expect(ExpectedCount.once(), requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/chat/completions"))
                .andRespond(request -> new org.springframework.mock.http.client.MockClientHttpResponse(stream, HttpStatus.OK));
        var previews = new java.util.ArrayList<String>();
        assertThatThrownBy(() -> new DeepSeekApiClient(builder, budget).streamPolish(
                "model_gateway", null, "synthetic", "xingshi", "合成验收正文", previews::add))
                .hasMessageContaining("连接中断").hasMessageContaining("原文已保留");
        assertThat(previews).containsExactly("半篇");
        org.mockito.Mockito.verify(budget, org.mockito.Mockito.times(1)).reserve(
                org.mockito.ArgumentMatchers.eq(DeepSeekPurpose.POLISH), org.mockito.ArgumentMatchers.anyLong());
        server.verify();
    }

    @Test
    void streamsOnlyContentAndSettlesFinalUsageOnceForGateway() {
        RestClient.Builder builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/chat/completions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"stream\":true")))
                .andRespond(withSuccess("""
                        : keepalive
                        data: {"choices":[{"delta":{"reasoning_content":"hidden"}}]}

                        data: {"choices":[{"delta":{"content":"你好"}}]}

                        data: {"choices":[{"delta":{"content":"，世界。"},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"total_tokens":31}}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));
        var reservation = new DeepSeekBudgetService.Reservation(java.time.LocalDate.now(), DeepSeekPurpose.POLISH, 20000);
        org.mockito.Mockito.when(budget.reserve(org.mockito.ArgumentMatchers.eq(DeepSeekPurpose.POLISH), org.mockito.ArgumentMatchers.anyLong())).thenReturn(reservation);
        var previews = new java.util.ArrayList<String>();
        var result = new DeepSeekApiClient(builder, budget).streamPolish("model_gateway", null, "test", "xingshi", "原文", previews::add);
        assertThat(previews).containsExactly("你好", "你好，世界。");
        assertThat(result).isEqualTo("你好，世界。");
        org.mockito.Mockito.verify(budget).settle(reservation, 31);
        server.verify();
    }

    @Test
    void interruptedOrTruncatedStreamsNeverSucceedOrRetry() {
        for (String ending : java.util.List.of("", "data: {\"choices\":[{\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n")) {
            RestClient.Builder builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/chat/completions"))
                    .andRespond(withSuccess("data: {\"choices\":[{\"delta\":{\"content\":\"半篇\"}}]}\n\n" + ending, MediaType.TEXT_EVENT_STREAM));
            assertThatThrownBy(() -> new DeepSeekApiClient(builder, budget).streamPolish("model_gateway", null, "test", "xingshi", "原文", ignored -> {}))
                    .hasMessageContaining("未完整");
            server.verify();
        }
        org.mockito.Mockito.verify(budget, org.mockito.Mockito.never()).settle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void gatewayDeniedPurposeCannotSendAnyCompletion() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        org.mockito.Mockito.when(budget.reserve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new BadRequestException("未获准"));
        var client = new DeepSeekApiClient(builder, budget);
        assertThat(client.allows("model_gateway", DeepSeekPurpose.UNKNOWN)).isFalse();
        assertThatThrownBy(() -> client.complete("model_gateway", null, "test", "xingshi", "规则", "原文"))
                .hasMessageContaining("未获准");
        assertThatThrownBy(() -> client.streamPolish("model_gateway", null, "test", "xingshi", "原文", ignored -> {}))
                .hasMessageContaining("未获准");
        server.verify();
    }

    @Test
    void missingPurposeAndDeniedBudgetNeverSendHttp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        org.mockito.Mockito.when(budget.reserve(org.mockito.ArgumentMatchers.eq(DeepSeekPurpose.UNKNOWN), org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new BadRequestException("未获准"));
        var client = new DeepSeekApiClient(builder, budget);
        assertThatThrownBy(() -> client.complete("deepseek", null, "secret", "deepseek-v4-flash", "规则", "材料"))
                .hasMessageContaining("未获准");
        server.verify();
    }

    @Test
    void chargesEveryRetryAndSettlesEvenTruncatedResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withSuccess("{\"usage\":{\"total_tokens\":90},\"choices\":[{\"finish_reason\":\"length\"}]}", MediaType.APPLICATION_JSON));
        var reservation = new DeepSeekBudgetService.Reservation(java.time.LocalDate.now(), DeepSeekPurpose.POLISH, 10000);
        org.mockito.Mockito.when(budget.reserve(org.mockito.ArgumentMatchers.eq(DeepSeekPurpose.POLISH), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(reservation);
        var client = new DeepSeekApiClient(builder, ignored -> {}, budget);
        assertThatThrownBy(() -> client.polish(null, "secret", "deepseek-v4-flash", "文字"))
                .hasMessageContaining("截断");
        org.mockito.Mockito.verify(budget, org.mockito.Mockito.times(2)).reserve(org.mockito.ArgumentMatchers.eq(DeepSeekPurpose.POLISH), org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(budget).settle(reservation, 90);
        server.verify();
    }

    @Test
    void onlyAcceptsOfficialDeepSeekApiHost() {
        DeepSeekApiClient client = new DeepSeekApiClient(RestClient.builder(), budget);

        assertThat(client.normalizeBaseUrl(null)).isEqualTo("https://api.deepseek.com");
        assertThat(client.normalizeBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com");
        assertThatThrownBy(() -> client.normalizeBaseUrl("https://proxy.example/v1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("只允许发送到");
    }

    @Test
    void modelGatewayOnlyAcceptsTheAssignedHttpsBasePath() {
        DeepSeekApiClient client = new DeepSeekApiClient(RestClient.builder(), budget);

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

        var models = new DeepSeekApiClient(builder, budget).models(null, "token-test");

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

        String result = new DeepSeekApiClient(builder, budget).polish(
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

        assertThatThrownBy(() -> new DeepSeekApiClient(builder, budget).polish(
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

        DeepSeekApiClient client = new DeepSeekApiClient(builder, ignored -> { }, budget);

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

        DeepSeekApiClient client = new DeepSeekApiClient(builder, ignored -> { }, budget);

        assertThatThrownBy(() -> client.complete("deepseek", null, "token-test", "deepseek-v4-flash",
                "系统规则", "只读材料"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("上游调用失败");
        server.verify();
    }

    @Test
    void completionDoesNotRetryCredentialErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        DeepSeekApiClient client = new DeepSeekApiClient(builder, ignored -> { }, budget);

        assertThatThrownBy(() -> client.complete("deepseek", null, "token-test", "deepseek-v4-flash",
                "系统规则", "只读材料"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("上游调用失败");
        server.verify();
    }

    @Test
    void modelGatewayUsesOpenAiV1RoutesAndBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/models"))
                .andExpect(header(AUTHORIZATION, "Bearer gateway-token"))
                .andRespond(withSuccess("""
                        {"object":"list","data":[{"id":"xingshi"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(DeepSeekApiClient.MODEL_GATEWAY_BASE_URL + "/chat/completions"))
                .andExpect(header(AUTHORIZATION, "Bearer gateway-token"))
                .andExpect(content().json("""
                        {"model":"xingshi","stream":false}
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"网关润色结果。"}}]}
                        """, MediaType.APPLICATION_JSON));
        DeepSeekApiClient client = new DeepSeekApiClient(builder, budget);

        assertThat(client.models("model_gateway", DeepSeekApiClient.MODEL_GATEWAY_BASE_URL, "gateway-token"))
                .containsExactly("xingshi");
        assertThat(client.polish("model_gateway", DeepSeekApiClient.MODEL_GATEWAY_BASE_URL,
                "gateway-token", "xingshi", "待润色内容。"))
                .isEqualTo("网关润色结果。");
        server.verify();
    }
}
