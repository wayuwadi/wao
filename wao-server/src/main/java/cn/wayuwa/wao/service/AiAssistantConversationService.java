package cn.wayuwa.wao.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.AiAssistantConversationEntity;
import cn.wayuwa.wao.domain.AiAssistantMessageEntity;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.dto.AssistantChatRequest.ChatMessage;
import cn.wayuwa.wao.dto.AssistantConversationDetailDto;
import cn.wayuwa.wao.dto.AssistantConversationDto;
import cn.wayuwa.wao.dto.AssistantMessageDto;
import cn.wayuwa.wao.mapper.AiAssistantConversationMapper;
import cn.wayuwa.wao.mapper.AiAssistantMessageMapper;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 按成员保存 AI 助手会话和消息，供聊天工作台跨页面恢复。 */
@Service
public class AiAssistantConversationService {

    private static final List<String> ACTIVE_RUNS = List.of("queued", "running");
    private static final String NEW_CHAT_TITLE = "新对话";

    private final AiAssistantConversationMapper conversationMapper;
    private final AiAssistantMessageMapper messageMapper;
    private final AiAssistantRunMapper runMapper;

    public AiAssistantConversationService(AiAssistantConversationMapper conversationMapper,
            AiAssistantMessageMapper messageMapper, AiAssistantRunMapper runMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
    }

    /** 返回当前成员最近更新的 50 条会话。 */
    public List<AssistantConversationDto> list(String memberId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AiAssistantConversationEntity>()
                .eq(AiAssistantConversationEntity::getMemberId, memberId)
                .orderByDesc(AiAssistantConversationEntity::getUpdatedAt)
                .last("LIMIT 50")).stream().map(this::toDto).toList();
    }

    /** 创建一条空白会话；首条消息写入时会自动生成标题。 */
    public AssistantConversationDto create(String memberId) {
        LocalDateTime now = LocalDateTime.now();
        AiAssistantConversationEntity conversation = new AiAssistantConversationEntity();
        conversation.setId(id("cv"));
        conversation.setMemberId(memberId);
        conversation.setTitle(NEW_CHAT_TITLE);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        return toDto(conversation);
    }

    /** 返回一条属于当前成员的会话及最近 200 条消息。 */
    public AssistantConversationDetailDto detail(String conversationId, String memberId) {
        AiAssistantConversationEntity conversation = owned(conversationId, memberId);
        List<AiAssistantMessageEntity> rows = messageMapper.selectList(
                new LambdaQueryWrapper<AiAssistantMessageEntity>()
                        .eq(AiAssistantMessageEntity::getConversationId, conversationId)
                        .orderByDesc(AiAssistantMessageEntity::getCreatedAt)
                        .last("LIMIT 200"));
        Collections.reverse(rows);
        List<AssistantMessageDto> messages = rows.stream()
                .map(row -> new AssistantMessageDto(row.getId(), row.getRole(), row.getContent(), row.getCreatedAt()))
                .toList();
        return new AssistantConversationDetailDto(toDto(conversation), messages);
    }

    /** 返回会话最近 10 条可信持久化消息，供 Codex 续接上下文。 */
    public List<ChatMessage> recentHistory(String conversationId, String memberId) {
        if (!StringUtils.hasText(conversationId)) return List.of();
        owned(conversationId, memberId);
        List<AiAssistantMessageEntity> rows = new ArrayList<>(messageMapper.selectList(
                new LambdaQueryWrapper<AiAssistantMessageEntity>()
                        .eq(AiAssistantMessageEntity::getConversationId, conversationId)
                        .orderByDesc(AiAssistantMessageEntity::getCreatedAt)
                        .last("LIMIT 10")));
        Collections.reverse(rows);
        return rows.stream().map(row -> new ChatMessage(row.getRole(), limit(row.getContent(), 4000))).toList();
    }

    /** 原子写入队列记录和用户消息，并更新会话标题与摘要。 */
    @Transactional
    public void enqueue(AiAssistantRunEntity run, String conversationId, String memberId, String userContent) {
        AiAssistantConversationEntity conversation = owned(conversationId, memberId);
        LocalDateTime now = LocalDateTime.now();
        AiAssistantMessageEntity message = new AiAssistantMessageEntity();
        message.setId(id("am"));
        message.setConversationId(conversationId);
        message.setRole("user");
        message.setContent(userContent.trim());
        message.setCreatedAt(now);
        run.setConversationId(conversationId);
        run.setUserMessageId(message.getId());

        runMapper.insert(run);
        messageMapper.insert(message);
        if (NEW_CHAT_TITLE.equals(conversation.getTitle())) conversation.setTitle(title(userContent));
        conversation.setLastMessagePreview(preview(userContent));
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
    }

    /** 保存成功的 Codex 回复并把会话移动到历史列表顶部。 */
    @Transactional
    public void appendAssistantReply(AiAssistantRunEntity run, String content) {
        if (!StringUtils.hasText(run.getConversationId()) || !StringUtils.hasText(content)) return;
        AiAssistantConversationEntity conversation = conversationMapper.selectById(run.getConversationId());
        if (conversation == null) return;
        LocalDateTime now = LocalDateTime.now();
        AiAssistantMessageEntity message = new AiAssistantMessageEntity();
        message.setId(id("am"));
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent(content);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        conversation.setLastMessagePreview(preview(content));
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
    }

    /** 删除当前成员的一条历史；仍有问答执行时拒绝删除，避免丢失进行中的答复。 */
    @Transactional
    public void delete(String conversationId, String memberId) {
        AiAssistantConversationEntity conversation = owned(conversationId, memberId);
        long active = runMapper.selectCount(new LambdaQueryWrapper<AiAssistantRunEntity>()
                .eq(AiAssistantRunEntity::getConversationId, conversationId)
                .in(AiAssistantRunEntity::getStatus, ACTIVE_RUNS));
        if (active > 0) throw new BadRequestException("当前会话仍在回答中，请完成后再删除");
        conversationMapper.deleteById(conversation.getId());
    }

    private AiAssistantConversationEntity owned(String conversationId, String memberId) {
        if (!StringUtils.hasText(conversationId)) throw new BadRequestException("请选择或新建一个 AI 会话");
        AiAssistantConversationEntity conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !memberId.equals(conversation.getMemberId())) {
            throw new NotFoundException("AI 会话不存在");
        }
        return conversation;
    }

    private AssistantConversationDto toDto(AiAssistantConversationEntity row) {
        return new AssistantConversationDto(
                row.getId(), row.getTitle(), row.getLastMessagePreview(), row.getUpdatedAt());
    }

    private static String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String title(String value) {
        String normalized = normalize(value);
        return normalized.length() <= 28 ? normalized : normalized.substring(0, 28) + "…";
    }

    private static String preview(String value) {
        String normalized = normalize(value);
        return normalized.length() <= 100 ? normalized : normalized.substring(0, 100) + "…";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
