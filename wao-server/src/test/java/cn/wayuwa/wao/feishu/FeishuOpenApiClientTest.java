package cn.wayuwa.wao.feishu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FeishuOpenApiClientTest {

    private static final String BASE_URL = "https://open.feishu.test";
    private static final String MESSAGES_URL = BASE_URL
            + "/open-apis/im/v1/messages?container_id_type=chat&container_id=oc_chat"
            + "&start_time=100&end_time=200&sort_type=ByCreateTimeAsc&page_size=50";

    @Test
    void addsReactionToTheOriginalMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_source/reactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-test"))
                .andExpect(content().json("{\"reaction_type\":{\"emoji_type\":\"DONE\"}}"))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"success","data":{"reaction_id":"reaction-1",
                         "reaction_type":{"emoji_type":"DONE"}}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.addMessageReaction("token-test", "om_source", "DONE")).isEqualTo("reaction-1");
        server.verify();
    }

    @Test
    void sendsNormalPostWithStableUuidAndReturnsMessageId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL
                + "/open-apis/im/v1/messages?receive_id_type=chat_id&uuid=msg1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-test"))
                .andExpect(request -> {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("receive_id").asText()).isEqualTo("oc_chat");
                    assertThat(body.path("msg_type").asText()).isEqualTo("post");
                    assertThat(mapper.readTree(body.path("content").asText())).isEqualTo(mapper.readTree("""
                            {"zh_cn":{"title":"","content":[[{"tag":"text","text":"你好"}]]}}
                            """));
                })
                .andRespond(withSuccess("""
                        {"code":0,"data":{"message_id":"om_created","msg_type":"post"}}
                        """, MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        String id = client.sendPostMessage("token-test", "oc_chat",
                java.util.Map.of("zh_cn", java.util.Map.of("title", "", "content",
                        List.of(List.of(java.util.Map.of("tag", "text", "text", "你好"))))),
                "msg1", null);

        assertThat(id).isEqualTo("om_created");
        server.verify();
    }

    @Test
    void repliesToOriginalFeishuMessageInsteadOfCreatingANewCard() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_parent/reply?uuid=msg2"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"message_id":"om_reply","parent_id":"om_parent"}}
                        """, MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        String id = client.sendPostMessage("token-test", "oc_chat", java.util.Map.of(),
                "msg2", "om_parent");

        assertThat(id).isEqualTo("om_reply");
        server.verify();
    }

    @Test
    void deletesReactionByTheReturnedReactionId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_source/reactions/reaction-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\"}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        client.deleteMessageReaction("token-test", "om_source", "reaction-1");

        server.verify();
    }

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
    void mapsChatAppOpenIdToUnionIdOnlyWhenNamesAreUnique() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL
                        + "/open-apis/im/v1/chats/oc_chat/members?member_id_type=open_id&page_size=100"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[
                          {"member_id":"ou_sender","name":"娃弟"},
                          {"member_id":"ou_other","name":"娃哥"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL
                        + "/open-apis/im/v1/chats/oc_chat/members?member_id_type=union_id&page_size=100"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[
                          {"member_id":"on_other","name":"娃哥"},
                          {"member_id":"on_sender","name":"娃弟"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.chatMemberUnionId("token-test", "oc_chat", "ou_sender"))
                .isEqualTo("on_sender");
        server.verify();
    }

    @Test
    void refusesToGuessUnionIdWhenOpenIdListHasDuplicateNames() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL
                        + "/open-apis/im/v1/chats/oc_chat/members?member_id_type=open_id&page_size=100"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[
                          {"member_id":"ou_sender","name":"同名"},
                          {"member_id":"ou_other","name":"同名"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.chatMemberUnionId("token-test", "oc_chat", "ou_sender")).isEmpty();
        server.verify();
    }

    @Test
    void mapsCreatorUnionIdToChatOpenIdOnlyWhenNamesAreUnique() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL
                        + "/open-apis/im/v1/chats/oc_chat/members?member_id_type=union_id&page_size=100"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[
                          {"member_id":"on_other","name":"娃哥"},
                          {"member_id":"on_sender","name":"娃弟"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL
                        + "/open-apis/im/v1/chats/oc_chat/members?member_id_type=open_id&page_size=100"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[
                          {"member_id":"ou_sender","name":"娃弟"},
                          {"member_id":"ou_other","name":"娃哥"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.chatMemberOpenIdByUnionId("token-test", "oc_chat", "on_sender"))
                .isEqualTo("ou_sender");
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
