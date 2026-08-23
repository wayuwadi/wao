package cn.wayuwa.wao.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FeishuOpenApiClientTest {

    private static final String BASE_URL = "https://open.feishu.test";
    private static final String MESSAGES_URL = BASE_URL
            + "/open-apis/im/v1/messages?container_id_type=chat&container_id=oc_chat"
            + "&start_time=100&end_time=200&sort_type=ByCreateTimeAsc&page_size=50";

    @Test
    void rejectsMaxPageResponseWhenFeishuStillHasMoreMessages() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectFortyPages(server, true);

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThatThrownBy(() -> client.listMessages("token-test", "oc_chat", 100, 200))
                .isInstanceOf(FeishuApiException.class)
                .hasMessageContaining("分页上限 40 页")
                .hasMessageContaining("仍有更多消息")
                .hasMessageContaining("避免保存不完整归档");
        server.verify();
    }

    @Test
    void returnsMessagesWhenLastAllowedPageIsComplete() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        expectFortyPages(server, false);

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        List<FeishuOpenApiClient.FeishuMessage> messages =
                client.listMessages("token-test", "oc_chat", 100, 200);

        assertThat(messages).hasSize(40);
        assertThat(messages.get(0).messageId()).isEqualTo("om_1");
        assertThat(messages.get(39).messageId()).isEqualTo("om_40");
        server.verify();
    }

    @Test
    void readsMentionOpenIdsFromMessageHistory() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
                {"code":0,"data":{"items":[{
                  "message_id":"om_mention",
                  "sender":{"id":"ou_sender","sender_type":"user"},
                  "msg_type":"text","body":{"content":"{\\"text\\":\\"@_user_1 记录任务\\"}"},
                  "create_time":150,"deleted":false,
                  "mentions":[{"key":"@_user_1","id":{"open_id":"ou_bot"},"name":"WAO"}]
                }],"has_more":false}}
                """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        var message = client.listMessages("token-test", "oc_chat", 100, 200).get(0);
        assertThat(message.mentions()).containsExactly(
                new FeishuOpenApiClient.FeishuMention("@_user_1", "ou_bot", "WAO"));
        server.verify();
    }

    @Test
    void readsCurrentBotOpenId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/bot/v3/info"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"bot":{"open_id":"ou_bot","app_name":"WAO"}}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.botOpenId("token-test")).isEqualTo("ou_bot");
        server.verify();
    }

    @Test
    void percentEncodesOpaqueFeishuPageTokensBeforeRequestingTheNextPage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
                {"code":0,"data":{"items":[],"has_more":true,"page_token":"AQD+next/token="}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(MESSAGES_URL + "&page_token=AQD%2Bnext%2Ftoken%3D"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[],"has_more":false}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.listMessages("token-test", "oc_chat", 100, 200)).isEmpty();
        server.verify();
    }

    @Test
    void rejectsHasMoreResponseWhenPageTokenIsMissing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
                {"code":0,"data":{"items":[{"message_id":"partial"}],"has_more":true}}
                """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThatThrownBy(() -> client.listMessages("token-test", "oc_chat", 100, 200))
                .isInstanceOf(FeishuApiException.class)
                .hasMessageContaining("has_more=true")
                .hasMessageContaining("page_token 为空")
                .hasMessageContaining("避免返回不完整消息");
        server.verify();
    }

    @Test
    void rejectsHasMoreResponseWhenPageTokenIsBlank() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
                {"code":0,"data":{"items":[],"has_more":true,"page_token":"  "}}
                """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThatThrownBy(() -> client.listMessages("token-test", "oc_chat", 100, 200))
                .isInstanceOf(FeishuApiException.class)
                .hasMessageContaining("has_more=true")
                .hasMessageContaining("page_token 为空");
        server.verify();
    }

    @Test
    void rejectsRepeatedMessagePageToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(MESSAGES_URL)).andRespond(withSuccess("""
                {"code":0,"data":{"items":[],"has_more":true,"page_token":"repeat"}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(MESSAGES_URL + "&page_token=repeat")).andRespond(withSuccess("""
                {"code":0,"data":{"items":[{"message_id":"partial"}],
                 "has_more":true,"page_token":"repeat"}}
                """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThatThrownBy(() -> client.listMessages("token-test", "oc_chat", 100, 200))
                .isInstanceOf(FeishuApiException.class)
                .hasMessageContaining("重复 page_token")
                .hasMessageContaining("避免返回不完整消息");
        server.verify();
    }

    private static void expectFortyPages(MockRestServiceServer server, boolean finalPageHasMore) {
        for (int page = 1; page <= 40; page++) {
            String pageToken = page == 1 ? "" : "&page_token=p" + page;
            boolean hasMore = page < 40 || finalPageHasMore;
            server.expect(requestTo(MESSAGES_URL + pageToken))
                    .andRespond(withSuccess(responseBody(page, hasMore), MediaType.APPLICATION_JSON));
        }
    }

    private static String responseBody(int page, boolean hasMore) {
        String pagination = hasMore
                ? ",\"has_more\":true,\"page_token\":\"p" + (page + 1) + "\""
                : ",\"has_more\":false";
        return "{\"code\":0,\"data\":{\"items\":[{"
                + "\"message_id\":\"om_" + page + "\","
                + "\"sender\":{\"id\":\"ou_test\",\"sender_type\":\"user\"},"
                + "\"msg_type\":\"text\","
                + "\"body\":{\"content\":\"message " + page + "\"},"
                + "\"create_time\":" + page + ","
                + "\"deleted\":false}]" + pagination + "}}";
    }
}
