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

    @Test
    void developmentDecisionUsesStableUuidBoundRevisionAndPlainText() throws Exception {
        RestClient.Builder builder=RestClient.builder();
        MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
        ObjectMapper json=new ObjectMapper();
        server.expect(requestTo(BASE_URL+"/open-apis/im/v1/messages?receive_id_type=open_id"))
            .andExpect(method(HttpMethod.POST)).andExpect(request->{
                JsonNode body=json.readTree(((MockClientHttpRequest)request).getBodyAsString());
                assertThat(body.path("uuid").asText()).isEqualTo("decision-fixture");
                assertThat(body.path("receive_id").asText()).isEqualTo("ou_owner");
                JsonNode card=json.readTree(body.path("content").asText());
                assertThat(card.path("elements").get(0).path("text").path("tag").asText()).isEqualTo("plain_text");
                var value=card.path("elements").get(2).path("actions").get(0).path("value");
                assertThat(value.path("revision").asLong()).isEqualTo(7);
                assertThat(value.path("decisionId").asText()).isEqualTo("decision-fixture");
            }).andRespond(withSuccess("{\"code\":0,\"data\":{\"message_id\":\"om_bound\"}}",MediaType.APPLICATION_JSON));
        assertThat(new FeishuOpenApiClient(builder,BASE_URL,null).sendDevelopmentDecision("test-token","ou_owner","decision-fixture",7,"采用？","理由"))
            .isEqualTo("om_bound");server.verify();
    }

    @Test
    void developmentDecisionCannotPretendSentWithoutMessageReceipt() {
        RestClient.Builder builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL+"/open-apis/im/v1/messages?receive_id_type=open_id"))
            .andRespond(withSuccess("{\"code\":0,\"data\":{}}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->new FeishuOpenApiClient(builder,BASE_URL,null).sendDevelopmentDecision("test-token","ou_owner","decision-fixture",7,"采用？","理由"))
            .isInstanceOf(FeishuApiException.class);server.verify();
    }

    @Test
    void actualHttpAttemptsAreCountedButCacheHitsAreNotAndStatisticsFailureDoesNotBreakReads() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/chats/oc_real/members?member_id_type=open_id&page_size=100"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"items\":[]}}", MediaType.APPLICATION_JSON));
        FeishuApiUsageRecorder usage = org.mockito.Mockito.mock(FeishuApiUsageRecorder.class);
        org.mockito.Mockito.doThrow(new RuntimeException("statistics unavailable")).when(usage)
                .record(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null, usage);
        assertThat(client.cachedChatMembers("token", "oc_real")).isEmpty();
        assertThat(client.cachedChatMembers("token", "oc_real")).isEmpty();
        org.mockito.Mockito.verify(usage).record(org.mockito.ArgumentMatchers.eq("GET"), org.mockito.ArgumentMatchers.any());
        server.verify();
    }

    @Test
    void backgroundMemberCacheReusesSnapshotButManualReadsStayFresh() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String endpoint = BASE_URL + "/open-apis/im/v1/chats/oc_real/members?member_id_type=open_id&page_size=100";
        server.expect(requestTo(endpoint)).andRespond(withSuccess(
                "{\"code\":0,\"data\":{\"items\":[{\"member_id\":\"ou_1\",\"name\":\"原名\"}]}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint)).andRespond(withSuccess(
                "{\"code\":0,\"data\":{\"items\":[{\"member_id\":\"ou_1\",\"name\":\"新名\"}]}}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThat(client.cachedChatMembers("token", "oc_real").get(0).name()).isEqualTo("原名");
        assertThat(client.cachedChatMembers("token", "oc_real").get(0).name()).isEqualTo("原名");
        assertThat(client.listChatMembers("token", "oc_real").get(0).name()).isEqualTo("新名");
        server.verify();
    }

    @Test
    void failedMemberReadIsNotCached() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String endpoint = BASE_URL + "/open-apis/im/v1/chats/oc_real/members?member_id_type=open_id&page_size=100";
        server.expect(requestTo(endpoint)).andRespond(withSuccess("{\"code\":99991403,\"msg\":\"quota\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(endpoint)).andRespond(withSuccess("{\"code\":0,\"data\":{\"items\":[]}}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThatThrownBy(() -> client.cachedChatMembers("token", "oc_real")).isInstanceOf(FeishuApiException.class);
        assertThat(client.cachedChatMembers("token", "oc_real")).isEmpty();
        server.verify();
    }

    @Test
    void sendsJsonV2CardWithUuidInBodyAndRequiresReceipt() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                    {"receive_id":"oc_group","msg_type":"interactive","content":"{\\"schema\\":\\"2.0\\"}","uuid":"tianren-reading"}
                    """))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"message_id\":\"om-real\"}}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThat(client.sendRawCard("test-token", "oc_group", new ObjectMapper().readTree("{\"schema\":\"2.0\"}"),
                "tianren-reading")).isEqualTo("om-real");
        server.verify();
    }
    @Test
    void feedbackCardsLinkToPersonalAuthorizationWithoutMemberSummary() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ObjectMapper json = new ObjectMapper();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("uuid").asText()).isEqualTo("n1");
                    var card = json.readTree(body.path("content").asText());
                    assertThat(card.path("config").path("update_multi").asBoolean()).isTrue();
                    assertThat(card.path("elements").get(1).path("actions")).hasSize(2);
                    assertThat(card.toString()).doesNotContain("成员反馈");
                }).andRespond(withSuccess("{\"code\":0,\"data\":{\"message_id\":\"om_original\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_original"))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer token"))
                .andExpect(request -> {
                    var body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    var card = json.readTree(body.path("content").asText());
                    assertThat(card.path("config").path("update_multi").asBoolean()).isTrue();
                    assertThat(card.toString()).contains("授权个人表情", "原始正文").doesNotContain("成员反馈", "ou_one", "ou_two");
                }).andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThat(client.sendFeedbackCard("token", "oc_group", "提醒", "原始正文", "n1")).isEqualTo("om_original");
        client.updateFeedbackCard("token", "om_original", client.feedbackCard("提醒", "原始正文", "n1",
                "✅ <at id=ou_one></at>：做到了\n❌ <at id=ou_two></at>：没做到"));
        server.verify();
    }

    @Test
    void legacyFeedbackReplyHasStableDeduplicationIdAndRequiresReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_original/reply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"msg_type\":\"interactive\",\"uuid\":\"feedback-n1\"}"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{}}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThatThrownBy(() -> client.replyFeedbackCard("token", "om_original",
                client.feedbackCard("成员反馈", "", "n1", ""), "feedback-n1"))
                .isInstanceOf(FeishuApiException.class).hasMessageContaining("回执");
        server.verify();
    }

    @Test
    void rejectedFeedbackUpdateIsNotReportedAsSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_original"))
                .andRespond(withSuccess("{\"code\":230001,\"msg\":\"rejected\"}", MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThatThrownBy(() -> client.updateFeedbackCard("token", "om_original",
                client.feedbackCard("提醒", "", "n1", "")))
                .isInstanceOf(FeishuApiException.class).hasMessageContaining("230001");
        server.verify();
    }

    @Test
    void personalReactionRequiresMatchingUserReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_one/reactions"))
                .andExpect(header("Authorization", "Bearer personal-token"))
                .andExpect(content().json("{\"reaction_type\":{\"emoji_type\":\"CheckMark\"}}"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"reaction_id\":\"r1\",\"operator\":{\"operator_type\":\"user\",\"operator_id\":\"ou_one\"}}}", MediaType.APPLICATION_JSON));
        var client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThat(client.addUserMessageReaction("personal-token", "om_one", "CheckMark", "ou_one")).isEqualTo("r1");
        server.verify();
    }

    @Test
    void botOrWrongUserReceiptIsRemovedAndNeverReportedAsPersonalSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_one/reactions"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"reaction_id\":\"r-wrong\",\"operator\":{\"operator_type\":\"app\",\"operator_id\":\"bot\"}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_one/reactions/r-wrong"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess("{\"code\":0}", MediaType.APPLICATION_JSON));
        var client = new FeishuOpenApiClient(builder, BASE_URL, null);
        assertThatThrownBy(() -> client.addUserMessageReaction("token", "om_one", "CheckMark", "ou_one"))
                .isInstanceOf(FeishuApiException.class).hasMessageContaining("作者与点击者不一致");
        server.verify();
    }

    @Test
    void manuallyRemovedOldReactionDoesNotBlockChangingChoice() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_one/reactions/r-gone"))
                .andExpect(header("Authorization", "Bearer personal-token"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON).body("{\"code\":231011}"));
        var client = new FeishuOpenApiClient(builder, BASE_URL, null);
        client.deleteOwnFeedbackReaction("personal-token", "om_one", "r-gone");
        server.verify();
    }

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
    void listsAuthenticatedUserReactionsWithStableReactionIds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_source/reactions"
                        + "?reaction_type=StatusInFlight&page_size=50&user_id_type=union_id"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-test"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[{
                          "reaction_id":"reaction-1",
                          "operator":{"operator_id":"on_owner","operator_type":"user"},
                          "action_time":"1787900000000",
                          "reaction_type":{"emoji_type":"StatusInFlight"}
                        }],"has_more":false}}
                        """, MediaType.APPLICATION_JSON));

        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        assertThat(client.listMessageReactions("token-test", "om_source", "StatusInFlight"))
                .containsExactly(new FeishuOpenApiClient.FeishuMessageReaction(
                        "reaction-1", "on_owner", "user", "1787900000000", "StatusInFlight"));
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
    void repliesWithPlainTextAndStableUuid() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE_URL + "/open-apis/im/v1/messages/om_parent/reply?uuid=polish-1"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-test"))
                .andExpect(request -> {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("msg_type").asText()).isEqualTo("text");
                    assertThat(mapper.readTree(body.path("content").asText()).path("text").asText())
                            .isEqualTo("润色结果：\n清楚的结果");
                })
                .andRespond(withSuccess("""
                        {"code":0,"data":{"message_id":"om_text_reply","parent_id":"om_parent"}}
                        """, MediaType.APPLICATION_JSON));
        FeishuOpenApiClient client = new FeishuOpenApiClient(builder, BASE_URL, null);

        String id = client.sendTextMessage("token-test", "oc_chat", "润色结果：\n清楚的结果",
                "polish-1", "om_parent");

        assertThat(id).isEqualTo("om_text_reply");
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
