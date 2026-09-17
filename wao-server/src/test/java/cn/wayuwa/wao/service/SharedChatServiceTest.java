package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.dto.UpdateSharedChatMessageRequest;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import cn.wayuwa.wao.domain.SharedChatMessageReadEntity;
import cn.wayuwa.wao.dto.CreateSharedChatMessageRequest;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageReadMapper;
import cn.wayuwa.wao.mapper.SharedChatReactionMapper;
import java.time.LocalDateTime;
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
    private final SharedChatService service =
            new SharedChatService(messages, reads, reactions, members, images);

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"THUMBSUP", "HEART", "SMILE", "DONE", "APPLAUSE", "FIRE", "PARTY", "THANKS", "LAUGH", "WOW", "THINKING", "SAD", "LOVE", "STRONG", "OK", "EYES", "ROCKET", "HUNDRED", "COFFEE", "STAR"})
    void supportsTwentyPersistedReactionTypes(String type) {
        SharedChatMessageEntity message = new SharedChatMessageEntity();
        message.setId("msg1"); message.setMemberId("m1"); message.setSource("WAO");
        message.setCreatedAt(LocalDateTime.now()); when(messages.selectById("msg1")).thenReturn(message);
        service.toggleReaction("m1", "msg1", type);
        var saved = ArgumentCaptor.forClass(cn.wayuwa.wao.domain.SharedChatReactionEntity.class);
        verify(reactions).insert(saved.capture()); assertThat(saved.getValue().getEmojiType()).isEqualTo(type);
    }

    @Test
    void persistsTextAndScreenshotLocally() {
        ImageBlobEntity image = new ImageBlobEntity();
        image.setId("img1");
        image.setName("桌面截图.png");
        image.setContent(new byte[] {1, 2, 3});
        when(images.selectById("img1")).thenReturn(image);
        var result = service.create("m1", new CreateSharedChatMessageRequest(
                "client_token_123", "看一下这张图", "img1"));

        assertThat(result.content()).isEqualTo("看一下这张图");
        assertThat(result.imageUrl()).isEqualTo("/api/images/img1/raw");
        assertThat(result.feishuStatus()).isEqualTo("LOCAL");
        ArgumentCaptor<SharedChatMessageEntity> saved = ArgumentCaptor.forClass(SharedChatMessageEntity.class);
        verify(messages).insert(saved.capture());
        assertThat(saved.getValue().getMemberId()).isEqualTo("m1");
        assertThat(saved.getValue().getSource()).isEqualTo("WAO");
    }

    @Test
    void savesWithoutExternalConfiguration() {
        var result = service.create("m1", new CreateSharedChatMessageRequest(
                "client_token_456", "WAO 内照常保存", null));

        assertThat(result.feishuStatus()).isEqualTo("LOCAL");
        verify(messages).insert(any(SharedChatMessageEntity.class));
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
    }

    @Test
    void reactionsPersistLocallyWithoutAnyFeishuIdentity() {
        SharedChatMessageEntity message = new SharedChatMessageEntity();
        message.setId("msg1"); message.setSource("WAO"); message.setMemberId("m1");
        when(messages.selectById("msg1")).thenReturn(message);
        service.toggleReaction("m1", "msg1", "HEART");
        var captured = ArgumentCaptor.forClass(cn.wayuwa.wao.domain.SharedChatReactionEntity.class);
        verify(reactions).insert(captured.capture());
        assertThat(captured.getValue().getReactorKey()).isEqualTo("member:m1");
        assertThat(captured.getValue().getEmojiType()).isEqualTo("HEART");
        assertThat(captured.getValue().getFeishuReactionId()).isNull();
    }

    @Test
    void rejectsReactionsToMissingMessagesAndUnsupportedEmoji() {
        assertThatThrownBy(() -> service.toggleReaction("m1", "missing", "HEART"))
                .isInstanceOf(cn.wayuwa.wao.common.NotFoundException.class);
        assertThatThrownBy(() -> service.toggleReaction("m1", "missing", "INVALID"))
                .isInstanceOf(BadRequestException.class);
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

    @Test
    void marksVisibleMessagesThroughTheCursorWithOneIdempotentBatch() {
        LocalDateTime at = LocalDateTime.of(2026, 8, 27, 6, 30);
        SharedChatMessageEntity before = message("a", at.minusSeconds(1));
        SharedChatMessageEntity through = message("m", at);
        SharedChatMessageEntity sameTimeAfter = message("z", at);
        SharedChatMessageEntity future = message("future", at.plusSeconds(1));
        when(messages.selectById("m")).thenReturn(through);
        when(messages.latest(100)).thenReturn(List.of(future, sameTimeAfter, through, before));

        service.markRead("m1", "m");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SharedChatMessageReadEntity>> rows =
                ArgumentCaptor.forClass(List.class);
        verify(reads).insertBatchIgnore(rows.capture());
        assertThat(rows.getValue()).extracting(SharedChatMessageReadEntity::getMessageId)
                .containsExactly("m", "a");
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.getReaderKey()).isEqualTo("member:m1");
            assertThat(row.getMemberId()).isEqualTo("m1");
            assertThat(row.getSource()).isEqualTo("WAO");
            assertThat(row.getReadAt()).isEqualTo(rows.getValue().get(0).getReadAt());
        });
        verify(reads, never()).insertIgnore(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void editsOnlyOwnMessageAndRetainsIdentityAndCreationTime() {
        SharedChatMessageEntity entity = message("edit1", LocalDateTime.of(2026, 9, 11, 10, 0));
        entity.setMemberId("m1"); entity.setSource("WAO"); entity.setDeleted(false); entity.setContent("原文");
        when(messages.selectById("edit1")).thenReturn(entity);
        when(messages.editContent(eq("edit1"), eq("m1"), eq("新文"), eq("原文"), any())).thenReturn(1);
        var result = service.edit("m1", "edit1", new UpdateSharedChatMessageRequest("新文", "原文"));
        assertThat(result.id()).isEqualTo("edit1");
        assertThat(result.content()).isEqualTo("新文");
        assertThat(result.createdAt()).isEqualTo(entity.getCreatedAt());
        verify(messages, never()).insert(any(SharedChatMessageEntity.class));
    }

    @Test
    void rejectsOtherAuthorsDeletedEmptyAndConcurrentChanges() {
        SharedChatMessageEntity entity = message("edit1", LocalDateTime.now());
        entity.setMemberId("m2"); entity.setSource("WAO"); entity.setDeleted(false); entity.setContent("原文");
        when(messages.selectById("edit1")).thenReturn(entity);
        var request = new UpdateSharedChatMessageRequest("新文", "原文");
        assertThatThrownBy(() -> service.edit("m1", "edit1", request)).isInstanceOf(ForbiddenException.class);
        entity.setMemberId("m1"); entity.setDeleted(true);
        assertThatThrownBy(() -> service.edit("m1", "edit1", request)).isInstanceOf(BadRequestException.class);
        entity.setDeleted(false);
        assertThatThrownBy(() -> service.edit("m1", "edit1", new UpdateSharedChatMessageRequest(" ", "原文")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.edit("m1", "edit1", request)).isInstanceOf(ConflictException.class);
        assertThat(entity.getContent()).isEqualTo("原文");
    }

    private static SharedChatMessageEntity message(String id, LocalDateTime createdAt) {
        SharedChatMessageEntity message = new SharedChatMessageEntity();
        message.setId(id);
        message.setCreatedAt(createdAt);
        return message;
    }
}
