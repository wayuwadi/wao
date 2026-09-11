package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.ConflictException;
import cn.wayuwa.wao.common.ForbiddenException;
import cn.wayuwa.wao.dto.UpdateSharedChatMessageRequest;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import cn.wayuwa.wao.domain.SharedChatMessageReadEntity;
import cn.wayuwa.wao.domain.SharedChatReactionEntity;
import cn.wayuwa.wao.dto.CreateSharedChatMessageRequest;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.dto.SharedChatMessageDto;
import cn.wayuwa.wao.dto.SharedChatReactionDto;
import cn.wayuwa.wao.dto.SharedChatReplyDto;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageReadMapper;
import cn.wayuwa.wao.mapper.SharedChatReactionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 组织共享聊天：可靠保存、阅读事实、引用和表情，全部在 WAO 内持久化。 */
@Service
public class SharedChatService {
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_CONTENT_CODEPOINTS = 2_000;
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Map<String, String> EMOJI_GLYPHS = Map.of(
            "THUMBSUP", "👍", "HEART", "❤️", "SMILE", "😊", "DONE", "✅");

    private final SharedChatMessageMapper messages;
    private final SharedChatMessageReadMapper reads;
    private final SharedChatReactionMapper reactions;
    private final MemberMapper members;
    private final ImageBlobMapper images;

    public SharedChatService(SharedChatMessageMapper messages, SharedChatMessageReadMapper reads,
            SharedChatReactionMapper reactions, MemberMapper members, ImageBlobMapper images) {
        this.messages = messages;
        this.reads = reads;
        this.reactions = reactions;
        this.members = members;
        this.images = images;
    }

    /** 读取最近消息或已知游标后的消息，并带上当前成员的真实已读与表情状态。 */
    public List<SharedChatMessageDto> list(String memberId, String afterId) {
        List<SharedChatMessageEntity> found;
        if (StringUtils.hasText(afterId)) {
            if (messages.selectById(afterId) == null) throw new BadRequestException("聊天消息游标已失效，请刷新列表");
            found = messages.after(afterId, MAX_MESSAGES);
        } else {
            found = new ArrayList<>(messages.latest(MAX_MESSAGES));
            Collections.reverse(found);
        }
        return toDtos(found, memberId);
    }

    /** 发送文字、Markdown 图文或引用回复；仅写入 WAO 消息流。 */
    public SharedChatMessageDto create(String memberId, CreateSharedChatMessageRequest request) {
        requireMember(memberId);
        String token = clientToken(request == null ? null : request.clientToken());
        SharedChatMessageEntity duplicate = messages.findByClientToken(memberId, token);
        if (duplicate != null) return toDtos(List.of(duplicate), memberId).get(0);

        String content = normalizeContent(request.content());
        ImageBlobEntity image = image(request.imageId());
        if (content.isEmpty() && image == null) throw new BadRequestException("请输入消息或选择一张截图");
        SharedChatMessageEntity reply = null;
        if (StringUtils.hasText(request.replyToId())) {
            reply = messages.selectById(request.replyToId().trim());
            if (reply == null || !"WAO".equals(reply.getSource())) throw new BadRequestException("引用的聊天消息不存在，请重新选择");
        }

        LocalDateTime now = LocalDateTime.now();
        SharedChatMessageEntity entity = new SharedChatMessageEntity();
        entity.setId(uuid());
        entity.setMemberId(memberId);
        entity.setClientToken(token);
        entity.setSource("WAO");
        entity.setContent(content);
        entity.setImageId(image == null ? null : image.getId());
        entity.setImageName(image == null ? null : image.getName());
        entity.setReplyToId(reply == null ? null : reply.getId());
        entity.setDeleted(false);
        entity.setFeishuStatus("LOCAL");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            messages.insert(entity);
        } catch (DuplicateKeyException exception) {
            SharedChatMessageEntity winner = messages.findByClientToken(memberId, token);
            if (winner == null) throw exception;
            return toDtos(List.of(winner), memberId).get(0);
        }
        markOneRead(entity.getId(), memberId, "WAO", now);
        return toDtos(List.of(entity), memberId).get(0);
    }

    /** 修改本人发送的 WAO 消息正文；条件写入防止不同窗口静默覆盖。 */
    public SharedChatMessageDto edit(String memberId, String id, UpdateSharedChatMessageRequest request) {
        requireMember(memberId);
        SharedChatMessageEntity entity = messages.selectById(id);
        if (entity == null) throw new NotFoundException("消息不存在");
        if (!memberId.equals(entity.getMemberId()) || !"WAO".equals(entity.getSource())) {
            throw new ForbiddenException("只能修改自己发送的消息");
        }
        if (Boolean.TRUE.equals(entity.getDeleted())) throw new BadRequestException("已撤回消息不能修改");
        if (request == null || request.expectedContent() == null) throw new BadRequestException("请先读取原消息");
        String content = normalizeContent(request.content());
        if (content.isEmpty() && !StringUtils.hasText(entity.getImageId())) throw new BadRequestException("消息不能为空");
        if (content.equals(entity.getContent())) return toDtos(List.of(entity), memberId).get(0);
        LocalDateTime now = LocalDateTime.now();
        if (messages.editContent(id, memberId, content, request.expectedContent(), now) != 1) {
            throw new ConflictException("消息已在其它窗口修改，请核对最新消息后重试；本机草稿已保留");
        }
        entity.setContent(content);
        entity.setUpdatedAt(now);
        return toDtos(List.of(entity), memberId).get(0);
    }

    /** 把页面真正展示到的最后一条及此前最近消息标为当前成员已读。 */
    public void markRead(String memberId, String throughId) {
        requireMember(memberId);
        SharedChatMessageEntity through = messages.selectById(throughId);
        if (through == null) throw new BadRequestException("要标记已读的消息不存在");
        LocalDateTime now = LocalDateTime.now();
        List<SharedChatMessageReadEntity> pending = new ArrayList<>();
        for (SharedChatMessageEntity message : messages.latest(MAX_MESSAGES)) {
            boolean notAfter = message.getCreatedAt().isBefore(through.getCreatedAt())
                    || (message.getCreatedAt().equals(through.getCreatedAt())
                    && message.getId().compareTo(through.getId()) <= 0);
            if (notAfter) pending.add(readRow(message.getId(), memberId, "WAO", now));
        }
        if (!pending.isEmpty()) reads.insertBatchIgnore(pending);
    }

    /** 切换当前成员对一条消息的表情回复，并返回刷新后的消息状态。 */
    public SharedChatMessageDto toggleReaction(String memberId, String id, String emojiType) {
        String emoji = emojiType == null ? "" : emojiType.trim();
        if (!EMOJI_GLYPHS.containsKey(emoji)) throw new BadRequestException("不支持该表情");
        MemberEntity member = requireMember(memberId);
        SharedChatMessageEntity message = messages.selectById(id);
        if (message == null || !"WAO".equals(message.getSource())) throw new NotFoundException("聊天消息不存在");
        String key = "member:" + memberId;
        if (reactions.find(id, key, emoji) != null) {
            reactions.deleteReaction(id, key, emoji);
        } else {
            SharedChatReactionEntity reaction = new SharedChatReactionEntity();
            reaction.setId(uuid());
            reaction.setMessageId(id);
            reaction.setReactorKey(key);
            reaction.setMemberId(memberId);
            reaction.setReactorName(member.getName());
            reaction.setEmojiType(emoji);
            reaction.setSource("WAO");
            reaction.setCreatedAt(LocalDateTime.now());
            try { reactions.insert(reaction); } catch (DuplicateKeyException ignored) { /* 并发重复点击已落库 */ }
        }
        return toDtos(List.of(message), memberId).get(0);
    }

    private List<SharedChatMessageDto> toDtos(List<SharedChatMessageEntity> entities, String currentMemberId) {
        if (entities.isEmpty()) return List.of();
        Map<String, MemberEntity> byMember = new LinkedHashMap<>();
        List<String> memberIds = entities.stream().map(SharedChatMessageEntity::getMemberId)
                .filter(StringUtils::hasText).distinct().toList();
        if (!memberIds.isEmpty()) members.selectBatchIds(memberIds).forEach(item -> byMember.put(item.getId(), item));

        Set<String> replyIds = new LinkedHashSet<>();
        entities.stream().map(SharedChatMessageEntity::getReplyToId).filter(StringUtils::hasText)
                .forEach(replyIds::add);
        Map<String, SharedChatMessageEntity> replyById = new LinkedHashMap<>();
        if (!replyIds.isEmpty()) messages.selectBatchIds(replyIds).forEach(item -> replyById.put(item.getId(), item));
        Set<String> replyMemberIds = new LinkedHashSet<>();
        replyById.values().stream().map(SharedChatMessageEntity::getMemberId).filter(StringUtils::hasText)
                .forEach(replyMemberIds::add);
        if (!replyMemberIds.isEmpty()) members.selectBatchIds(replyMemberIds)
                .forEach(item -> byMember.putIfAbsent(item.getId(), item));

        List<String> ids = entities.stream().map(SharedChatMessageEntity::getId).toList();
        List<SharedChatMessageReadEntity> readRows = reads.selectList(
                new LambdaQueryWrapper<SharedChatMessageReadEntity>().eq(SharedChatMessageReadEntity::getSource, "WAO").in(SharedChatMessageReadEntity::getMessageId, ids));
        List<SharedChatReactionEntity> reactionRows = reactions.selectList(
                new LambdaQueryWrapper<SharedChatReactionEntity>().eq(SharedChatReactionEntity::getSource, "WAO").in(SharedChatReactionEntity::getMessageId, ids));
        Map<String, List<SharedChatMessageReadEntity>> readsByMessage = groupReads(readRows);
        Map<String, List<SharedChatReactionEntity>> reactionsByMessage = groupReactions(reactionRows);

        List<SharedChatMessageDto> out = new ArrayList<>();
        for (SharedChatMessageEntity entity : entities) {
            MemberDto author = author(entity, byMember.get(entity.getMemberId()));
            SharedChatMessageEntity parent = replyById.get(entity.getReplyToId());
            SharedChatReplyDto reply = parent == null ? null : new SharedChatReplyDto(parent.getId(),
                    authorName(parent, byMember.get(parent.getMemberId())), preview(parent));
            List<SharedChatMessageReadEntity> messageReads = readsByMessage.getOrDefault(entity.getId(), List.of());
            String currentKey = "member:" + currentMemberId;
            int readCount = (int) messageReads.stream().map(SharedChatMessageReadEntity::getReaderKey).distinct().count();
            int feishuReadCount = (int) messageReads.stream().filter(row -> "FEISHU".equals(row.getSource()))
                    .map(SharedChatMessageReadEntity::getReaderKey).distinct().count();
            boolean read = messageReads.stream().anyMatch(row -> currentKey.equals(row.getReaderKey()));
            out.add(new SharedChatMessageDto(entity.getId(), author, entity.getContent(), entity.getImageId(),
                    entity.getImageName(), StringUtils.hasText(entity.getImageId())
                    ? "/api/images/" + entity.getImageId() + "/raw" : null,
                    entity.getSource(), reply, Boolean.TRUE.equals(entity.getDeleted()), read, readCount,
                    feishuReadCount, reactionDtos(reactionsByMessage.getOrDefault(entity.getId(), List.of()), currentKey),
                    entity.getFeishuStatus(), entity.getFeishuError(), entity.getCreatedAt()));
        }
        return out;
    }

    private static Map<String, List<SharedChatMessageReadEntity>> groupReads(
            List<SharedChatMessageReadEntity> rows) {
        Map<String, List<SharedChatMessageReadEntity>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>()).add(row));
        return grouped;
    }

    private static Map<String, List<SharedChatReactionEntity>> groupReactions(
            List<SharedChatReactionEntity> rows) {
        Map<String, List<SharedChatReactionEntity>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getMessageId(), ignored -> new ArrayList<>()).add(row));
        return grouped;
    }

    private static List<SharedChatReactionDto> reactionDtos(List<SharedChatReactionEntity> rows, String currentKey) {
        Map<String, List<SharedChatReactionEntity>> byEmoji = new LinkedHashMap<>();
        rows.forEach(row -> byEmoji.computeIfAbsent(row.getEmojiType(), ignored -> new ArrayList<>()).add(row));
        return byEmoji.entrySet().stream().map(entry -> new SharedChatReactionDto(entry.getKey(),
                EMOJI_GLYPHS.getOrDefault(entry.getKey(), "🙂"), entry.getValue().size(),
                entry.getValue().stream().anyMatch(row -> currentKey.equals(row.getReactorKey())),
                entry.getValue().stream().map(SharedChatReactionEntity::getReactorName).distinct().toList())).toList();
    }

    private MemberDto author(SharedChatMessageEntity entity, MemberEntity member) {
        if (member != null) return new MemberDto(member.getId(), member.getName(), member.getInitial(),
                member.getColor(), member.getRole());
        String name = StringUtils.hasText(entity.getExternalSenderName()) ? entity.getExternalSenderName() : "飞书成员";
        String initial = name.substring(0, name.offsetByCodePoints(0, 1));
        String id = "feishu:" + (StringUtils.hasText(entity.getExternalSenderId())
                ? entity.getExternalSenderId() : entity.getId());
        return new MemberDto(id, name, initial, "#5B8FF9", "飞书");
    }

    private static String authorName(SharedChatMessageEntity entity, MemberEntity member) {
        return member != null ? member.getName() : StringUtils.hasText(entity.getExternalSenderName())
                ? entity.getExternalSenderName() : "飞书成员";
    }

    private static String preview(SharedChatMessageEntity entity) {
        if (Boolean.TRUE.equals(entity.getDeleted())) return "消息已撤回";
        String value = entity.getContent() == null ? "" : entity.getContent().replaceAll("\\s+", " ").trim();
        if (value.isEmpty() && StringUtils.hasText(entity.getImageId())) return "[图片]";
        return value.codePointCount(0, value.length()) <= 80 ? value
                : value.substring(0, value.offsetByCodePoints(0, 80)) + "…";
    }

    private void markOneRead(String messageId, String memberId, String source, LocalDateTime at) {
        reads.insertIgnore(uuid(), messageId, "member:" + memberId, memberId, source, at);
    }

    private static SharedChatMessageReadEntity readRow(String messageId, String memberId,
            String source, LocalDateTime at) {
        SharedChatMessageReadEntity row = new SharedChatMessageReadEntity();
        row.setId(uuid());
        row.setMessageId(messageId);
        row.setReaderKey("member:" + memberId);
        row.setMemberId(memberId);
        row.setSource(source);
        row.setReadAt(at);
        return row;
    }

    private MemberEntity requireMember(String memberId) {
        MemberEntity member = members.selectById(memberId);
        if (member == null) throw new NotFoundException("当前成员不存在");
        return member;
    }

    private ImageBlobEntity image(String imageId) {
        if (!StringUtils.hasText(imageId)) return null;
        ImageBlobEntity image = images.selectById(imageId.trim());
        if (image == null || image.getContent() == null) throw new BadRequestException("所选截图不存在，请重新上传");
        return image;
    }

    private static String clientToken(String value) {
        String token = value == null ? "" : value.trim();
        if (!CLIENT_TOKEN.matcher(token).matches()) throw new BadRequestException("clientToken 格式不正确");
        return token;
    }

    private static String normalizeContent(String value) {
        String content = value == null ? "" : value.strip();
        if (content.codePointCount(0, content.length()) > MAX_CONTENT_CODEPOINTS) {
            throw new BadRequestException("消息最多 2000 个字符");
        }
        return content;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
