package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AiAssistantConversationEntity;
import cn.wayuwa.wao.domain.AiAssistantMessageEntity;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.mapper.AiAssistantConversationMapper;
import cn.wayuwa.wao.mapper.AiAssistantMessageMapper;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiAssistantConversationServiceTest {

    private final AiAssistantConversationMapper conversationMapper = mock(AiAssistantConversationMapper.class);
    private final AiAssistantMessageMapper messageMapper = mock(AiAssistantMessageMapper.class);
    private final AiAssistantRunMapper runMapper = mock(AiAssistantRunMapper.class);
    private final AiAssistantConversationService service = new AiAssistantConversationService(
            conversationMapper, messageMapper, runMapper);

    @Test
    void enqueuePersistsUserMessageAndGeneratesConversationTitle() {
        AiAssistantConversationEntity conversation = conversation("cv1", "member-1");
        when(conversationMapper.selectById("cv1")).thenReturn(conversation);
        AiAssistantRunEntity run = new AiAssistantRunEntity();
        run.setId("ac1");

        service.enqueue(run, "cv1", "member-1", "帮我整理这周的工作计划");

        ArgumentCaptor<AiAssistantMessageEntity> message = ArgumentCaptor.forClass(AiAssistantMessageEntity.class);
        verify(runMapper).insert(run);
        verify(messageMapper).insert(message.capture());
        assertThat(message.getValue().getRole()).isEqualTo("user");
        assertThat(message.getValue().getContent()).isEqualTo("帮我整理这周的工作计划");
        assertThat(run.getConversationId()).isEqualTo("cv1");
        assertThat(run.getUserMessageId()).isEqualTo(message.getValue().getId());
        assertThat(conversation.getTitle()).isEqualTo("帮我整理这周的工作计划");
        assertThat(conversation.getLastMessagePreview()).isEqualTo("帮我整理这周的工作计划");
        verify(conversationMapper).updateById(conversation);
    }

    @Test
    void detailRejectsConversationOwnedByAnotherMember() {
        when(conversationMapper.selectById("cv1")).thenReturn(conversation("cv1", "member-2"));

        assertThatThrownBy(() -> service.detail("cv1", "member-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void deleteRejectsConversationWithActiveRun() {
        when(conversationMapper.selectById("cv1")).thenReturn(conversation("cv1", "member-1"));
        when(runMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("cv1", "member-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("回答中");
    }

    private AiAssistantConversationEntity conversation(String id, String memberId) {
        AiAssistantConversationEntity conversation = new AiAssistantConversationEntity();
        conversation.setId(id);
        conversation.setMemberId(memberId);
        conversation.setTitle("新对话");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversation;
    }
}
