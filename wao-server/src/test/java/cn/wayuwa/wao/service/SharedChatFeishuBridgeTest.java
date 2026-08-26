package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import cn.wayuwa.wao.domain.SharedChatReactionEntity;
import cn.wayuwa.wao.dto.SaveSharedChatConfigRequest;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChat;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageReadMapper;
import cn.wayuwa.wao.mapper.SharedChatReactionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SharedChatFeishuBridgeTest {
    private final SettingService settings = mock(SettingService.class);
    private final FeishuOpenApiClient feishu = mock(FeishuOpenApiClient.class);
    private final SharedChatMessageMapper messages = mock(SharedChatMessageMapper.class);
    private final SharedChatMessageReadMapper reads = mock(SharedChatMessageReadMapper.class);
    private final SharedChatReactionMapper reactions = mock(SharedChatReactionMapper.class);
    private final ImageBlobMapper imageBlobs = mock(ImageBlobMapper.class);
    private final ImageService imageService = mock(ImageService.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final AccountIdentityMapper identities = mock(AccountIdentityMapper.class);
    private final SharedChatFeishuBridge bridge = new SharedChatFeishuBridge(settings, feishu, messages,
            reads, reactions, imageBlobs, imageService, members, accounts, identities, new ObjectMapper());

    @BeforeEach
    void app() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("app");
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")).thenReturn("secret");
        when(feishu.tenantAccessToken("app", "secret")).thenReturn("token");
    }

    @Test
    void savesOnlyAGroupTheConfiguredAppActuallyJoined() {
        when(feishu.listChats("token")).thenReturn(List.of(new FeishuChat("oc_real", "娃屋日常")));
        when(settings.get(SettingService.SHARED_CHAT_CHAT_ID, "")).thenReturn("oc_real");
        when(settings.get(SettingService.SHARED_CHAT_CHAT_NAME, "")).thenReturn("娃屋日常");

        var result = bridge.saveConfig(new SaveSharedChatConfigRequest("oc_real"));

        verify(settings).put(SettingService.SHARED_CHAT_CHAT_ID, "oc_real");
        verify(settings).put(SettingService.SHARED_CHAT_CHAT_NAME, "娃屋日常");
        assertThat(result.configured()).isTrue();
    }

    @Test
    void savesThePollingIntervalTogetherWithTheGroup() {
        when(feishu.listChats("token")).thenReturn(List.of(new FeishuChat("oc_real", "娃屋日常")));
        when(settings.get(SettingService.SHARED_CHAT_POLL_INTERVAL_SECONDS, "15")).thenReturn("60");

        var result = bridge.saveConfig(new SaveSharedChatConfigRequest("oc_real", 60));

        verify(settings).put(SettingService.SHARED_CHAT_POLL_INTERVAL_SECONDS, "60");
        assertThat(result.pollIntervalSeconds()).isEqualTo(60);
    }

    @Test
    void sendsNormalPostAndPersistsReturnedFeishuMessageId() {
        when(settings.get(SettingService.SHARED_CHAT_CHAT_ID, "")).thenReturn("oc_real");
        when(settings.get(SettingService.SHARED_CHAT_CHAT_NAME, "")).thenReturn("娃屋日常");
        when(feishu.sendPostMessage(eq("token"), eq("oc_real"), any(), eq("msg1"), eq(null)))
                .thenReturn("om_real");
        MemberEntity member = member();
        SharedChatMessageEntity message = message("msg1", "**重点**\n第二行");

        bridge.deliver(message, member);

        verify(feishu).sendPostMessage(eq("token"), eq("oc_real"), any(), eq("msg1"), eq(null));
        assertThat(message.getFeishuMessageId()).isEqualTo("om_real");
        assertThat(message.getFeishuStatus()).isEqualTo("SENT");
        verify(messages).updateById(message);
    }

    @Test
    void importsRealFeishuUserMessageWithStableExternalIdentity() {
        when(feishu.chatMemberUnionId("token", "oc_real", "ou_sender")).thenReturn("");
        FeishuMessage source = new FeishuMessage("om_in", "ou_sender", "user", "text",
                "{\"text\":\"飞书发来的话\"}", 1_787_700_000_000L, false);

        bridge.importMessage("token", "oc_real", "娃屋日常", source, Map.of("ou_sender", "娃弟"));

        ArgumentCaptor<SharedChatMessageEntity> captured = ArgumentCaptor.forClass(SharedChatMessageEntity.class);
        verify(messages).insert(captured.capture());
        assertThat(captured.getValue().getSource()).isEqualTo("FEISHU");
        assertThat(captured.getValue().getFeishuMessageId()).isEqualTo("om_in");
        assertThat(captured.getValue().getExternalSenderName()).isEqualTo("娃弟");
        assertThat(captured.getValue().getContent()).isEqualTo("飞书发来的话");
    }

    @Test
    void keepsAllImagesFromOneFeishuPostInOneBubbleAndInOrder() {
        when(feishu.downloadImage("token", "om_images", "img-a")).thenReturn(new byte[] {1});
        when(feishu.downloadImage("token", "om_images", "img-b")).thenReturn(new byte[] {2});
        when(imageService.store(any(), eq("image/png"), any(), eq("飞书聊天 · 娃屋日常"), eq(true)))
                .thenReturn(new ImageItemDto("a", "a.png", "1 B", "刚刚", "飞书", "/api/images/a/raw",
                                null, null),
                        new ImageItemDto("b", "b.png", "1 B", "刚刚", "飞书", "/api/images/b/raw",
                                null, null));
        FeishuMessage source = new FeishuMessage("om_images", "ou_sender", "user", "post", """
                {"zh_cn":{"title":"","content":[[
                  {"tag":"text","text":"两张图"},
                  {"tag":"img","image_key":"img-a"},
                  {"tag":"img","image_key":"img-b"}
                ]]}}
                """, 1_787_700_000_000L, false);

        bridge.importMessage("token", "oc_real", "娃屋日常", source, Map.of("ou_sender", "娃弟"));

        ArgumentCaptor<SharedChatMessageEntity> captured = ArgumentCaptor.forClass(SharedChatMessageEntity.class);
        verify(messages).insert(captured.capture());
        SharedChatMessageEntity imported = captured.getValue();
        assertThat(imported.getImageId()).isNull();
        assertThat(imported.getContent()).contains("两张图", "/api/images/a/raw", "/api/images/b/raw");
        assertThat(imported.getContent().indexOf("/api/images/a/raw"))
                .isLessThan(imported.getContent().indexOf("/api/images/b/raw"));
    }

    @Test
    void recordsOfficialReadAndReactionEventsWithoutGuessing() throws Exception {
        SharedChatMessageEntity message = message("msg1", "消息");
        when(messages.findByFeishuMessageId("om_real")).thenReturn(message);
        ObjectMapper json = new ObjectMapper();

        bridge.handleEvent(json.readTree("""
                {"header":{"event_type":"im.message.message_read_v1"},"event":{
                  "reader":{"reader_id":{"open_id":"ou_reader"},"read_time":"1787700000000"},
                  "message_id_list":["om_real"]}}
                """));
        bridge.handleEvent(json.readTree("""
                {"header":{"event_type":"im.message.reaction.created_v1"},"event":{
                  "message_id":"om_real","operator_type":"user","user_id":{"open_id":"ou_reader"},
                  "reaction_type":{"emoji_type":"HEART"},"action_time":"1787700000000"}}
                """));

        verify(reads).insertIgnore(any(), eq("msg1"), eq("feishu:ou_reader"), eq(null),
                eq("FEISHU"), any(LocalDateTime.class));
        ArgumentCaptor<SharedChatReactionEntity> reaction = ArgumentCaptor.forClass(SharedChatReactionEntity.class);
        verify(reactions).insert(reaction.capture());
        assertThat(reaction.getValue().getEmojiType()).isEqualTo("HEART");
        assertThat(reaction.getValue().getSource()).isEqualTo("FEISHU");
    }

    private static MemberEntity member() {
        MemberEntity member = new MemberEntity();
        member.setId("m1");
        member.setName("娃哥");
        member.setInitial("娃");
        member.setColor("#6655BE");
        member.setRole("管理员");
        return member;
    }

    private static SharedChatMessageEntity message(String id, String content) {
        SharedChatMessageEntity message = new SharedChatMessageEntity();
        message.setId(id);
        message.setContent(content);
        message.setSource("WAO");
        message.setFeishuStatus("PENDING");
        message.setCreatedAt(LocalDateTime.now());
        message.setUpdatedAt(LocalDateTime.now());
        return message;
    }
}
