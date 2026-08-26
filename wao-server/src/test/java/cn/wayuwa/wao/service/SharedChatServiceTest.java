package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import cn.wayuwa.wao.dto.CreateSharedChatMessageRequest;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageReadMapper;
import cn.wayuwa.wao.mapper.SharedChatReactionMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SharedChatServiceTest {
    private final SharedChatMessageMapper messages = mock(SharedChatMessageMapper.class);
    private final SharedChatMessageReadMapper reads = mock(SharedChatMessageReadMapper.class);
    private final SharedChatReactionMapper reactions = mock(SharedChatReactionMapper.class);
    private final MemberMapper members = mock(MemberMapper.class);
    private final ImageBlobMapper images = mock(ImageBlobMapper.class);
    private final SharedChatFeishuBridge bridge = mock(SharedChatFeishuBridge.class);
    private final SharedChatService service =
            new SharedChatService(messages, reads, reactions, members, images, bridge);

    @BeforeEach
    void memberExists() {
        MemberEntity member = new MemberEntity();
        member.setId("m1");
        member.setName("娃哥");
        member.setInitial("娃");
        member.setColor("#6655BE");
        member.setRole("管理员");
        when(members.selectById("m1")).thenReturn(member);
        when(reads.selectList(any())).thenReturn(List.of());
        when(reactions.selectList(any())).thenReturn(List.of());
    }

    @Test
    void persistsBeforeSendingTextAndScreenshotToConfiguredWebhook() {
        ImageBlobEntity image = new ImageBlobEntity();
        image.setId("img1");
        image.setName("桌面截图.png");
        image.setContent(new byte[] {1, 2, 3});
        when(images.selectById("img1")).thenReturn(image);
        var result = service.create("m1", new CreateSharedChatMessageRequest(
                "client_token_123", "看一下这张图", "img1"));

        assertThat(result.content()).isEqualTo("看一下这张图");
        assertThat(result.imageUrl()).isEqualTo("/api/images/img1/raw");
        assertThat(result.feishuStatus()).isEqualTo("PENDING");
        ArgumentCaptor<SharedChatMessageEntity> saved = ArgumentCaptor.forClass(SharedChatMessageEntity.class);
        verify(messages).insert(saved.capture());
        assertThat(saved.getValue().getMemberId()).isEqualTo("m1");
        assertThat(saved.getValue().getSource()).isEqualTo("WAO");
        verify(bridge).deliver(saved.getValue(), members.selectById("m1"));
    }

    @Test
    void keepsMessageWhenFeishuBridgeIsUnavailable() {
        var result = service.create("m1", new CreateSharedChatMessageRequest(
                "client_token_456", "WAO 内照常保存", null));

        assertThat(result.feishuStatus()).isEqualTo("PENDING");
        verify(messages).insert(any(SharedChatMessageEntity.class));
        verify(bridge).deliver(any(SharedChatMessageEntity.class), any(MemberEntity.class));
    }

    @Test
    void returnsExistingMessageForSameMemberAndClientTokenWithoutDuplicateDelivery() {
        SharedChatMessageEntity existing = new SharedChatMessageEntity();
        existing.setId("msg1");
        existing.setMemberId("m1");
        existing.setClientToken("client_token_789");
        existing.setContent("已经发过");
        existing.setFeishuStatus("SENT");
        when(messages.findByClientToken("m1", "client_token_789")).thenReturn(existing);

        var result = service.create("m1", new CreateSharedChatMessageRequest(
                "client_token_789", "重复请求", null));

        assertThat(result.id()).isEqualTo("msg1");
        assertThat(result.content()).isEqualTo("已经发过");
        verify(messages, never()).insert(any(SharedChatMessageEntity.class));
        verify(bridge, never()).deliver(any(), any());
    }

    @Test
    void rejectsEmptyAndOverlongMessages() {
        assertThatThrownBy(() -> service.create("m1",
                new CreateSharedChatMessageRequest("client_token_empty", "   ", null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("请输入消息");
        assertThatThrownBy(() -> service.create("m1",
                new CreateSharedChatMessageRequest("client_token_long", "好".repeat(2_001), null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("2000");
    }
}
