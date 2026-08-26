package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import cn.wayuwa.wao.dto.CreateSharedChatMessageRequest;
import cn.wayuwa.wao.dto.MemberDto;
import cn.wayuwa.wao.dto.SharedChatMessageDto;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.ImageBlobMapper;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.SharedChatMessageMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 组织共享轻量聊天：可靠保存消息，并把飞书出站失败收口为可见、可重试状态。 */
@Service
public class SharedChatService {
    static final String SENT = "SENT";
    static final String FAILED = "FAILED";
    static final String UNAVAILABLE = "UNAVAILABLE";
    private static final String PENDING = "PENDING";
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_CONTENT_CODEPOINTS = 2_000;
    private static final Pattern CLIENT_TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final SharedChatMessageMapper messages;
    private final MemberMapper members;
    private final ImageBlobMapper images;
    private final FeishuWebhookConfigService webhooks;
    private final FeishuWebhookNotifier notifier;
    private final FeishuAppLinks links;

    public SharedChatService(SharedChatMessageMapper messages, MemberMapper members, ImageBlobMapper images,
            FeishuWebhookConfigService webhooks, FeishuWebhookNotifier notifier, FeishuAppLinks links) {
        this.messages = messages;
        this.members = members;
        this.images = images;
        this.webhooks = webhooks;
        this.notifier = notifier;
        this.links = links;
    }

    /** 读取最近消息，或从一个已知消息游标之后增量读取。 */
    public List<SharedChatMessageDto> list(String afterId) {
        List<SharedChatMessageEntity> found;
        if (StringUtils.hasText(afterId)) {
            if (messages.selectById(afterId) == null) throw new BadRequestException("聊天消息游标已失效，请刷新列表");
            found = messages.after(afterId, MAX_MESSAGES);
        } else {
            found = new ArrayList<>(messages.latest(MAX_MESSAGES));
            Collections.reverse(found);
        }
        return toDtos(found);
    }

    /**
     * 发送一条文字和/或截图消息。数据库落库成功后才尝试飞书，因此外部故障不会丢失站内聊天。
     */
    public SharedChatMessageDto create(String memberId, CreateSharedChatMessageRequest request) {
        MemberEntity member = requireMember(memberId);
        String token = clientToken(request == null ? null : request.clientToken());
        SharedChatMessageEntity duplicate = messages.findByClientToken(memberId, token);
        if (duplicate != null) return toDto(duplicate, member);

        String content = normalizeContent(request.content());
        ImageBlobEntity image = image(request.imageId());
        if (content.isEmpty() && image == null) throw new BadRequestException("请输入消息或选择一张截图");

        LocalDateTime now = LocalDateTime.now();
        SharedChatMessageEntity entity = new SharedChatMessageEntity();
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setMemberId(memberId);
        entity.setClientToken(token);
        entity.setContent(content);
        entity.setImageId(image == null ? null : image.getId());
        entity.setImageName(image == null ? null : image.getName());
        entity.setFeishuStatus(PENDING);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            messages.insert(entity);
        } catch (DuplicateKeyException exception) {
            SharedChatMessageEntity winner = messages.findByClientToken(memberId, token);
            if (winner == null) throw exception;
            return toDto(winner, member);
        }
        deliver(entity, member);
        return toDto(entity, member);
    }

    /** 重试一条尚未同步成功的消息；成功消息不会被重复投递。 */
    public SharedChatMessageDto retry(String id) {
        SharedChatMessageEntity entity = messages.selectById(id);
        if (entity == null) throw new NotFoundException("聊天消息不存在");
        MemberEntity member = requireMember(entity.getMemberId());
        if (!SENT.equals(entity.getFeishuStatus())) deliver(entity, member);
        return toDto(entity, member);
    }

    private void deliver(SharedChatMessageEntity entity, MemberEntity member) {
        var target = webhooks.defaultTarget();
        if (target.isEmpty() || !target.get().enabled()) {
            updateDelivery(entity, UNAVAILABLE, "系统默认飞书机器人未配置或已停用");
            return;
        }
        try {
            String body = markdownSafe(entity.getContent());
            if (StringUtils.hasText(entity.getImageId())) {
                String imageUrl = FeishuAppLinks.SITE + "/api/images/" + entity.getImageId() + "/raw";
                body += (body.isBlank() ? "" : "\n\n") + "[查看截图：" + markdownSafe(entity.getImageName())
                        + "](" + imageUrl + ")";
            }
            notifier.send(target.get().url(), new FeishuWebhookNotifier.Card(
                    "WAO 聊天 · " + member.getName(), "blue",
                    List.of(List.of("发送者", markdownSafe(member.getName()))), body,
                    "打开 WAO 聊天", links.open("/chat")));
            updateDelivery(entity, SENT, null);
        } catch (RuntimeException exception) {
            updateDelivery(entity, FAILED, "同步失败，请稍后重试");
        }
    }

    private void updateDelivery(SharedChatMessageEntity entity, String status, String error) {
        entity.setFeishuStatus(status);
        entity.setFeishuError(error);
        entity.setUpdatedAt(LocalDateTime.now());
        messages.updateById(entity);
    }

    private List<SharedChatMessageDto> toDtos(List<SharedChatMessageEntity> entities) {
        if (entities.isEmpty()) return List.of();
        Map<String, MemberEntity> byId = new LinkedHashMap<>();
        members.selectBatchIds(entities.stream().map(SharedChatMessageEntity::getMemberId).distinct().toList())
                .forEach(member -> byId.put(member.getId(), member));
        return entities.stream().map(entity -> toDto(entity,
                byId.getOrDefault(entity.getMemberId(), unknownMember(entity.getMemberId())))).toList();
    }

    private SharedChatMessageDto toDto(SharedChatMessageEntity entity, MemberEntity member) {
        return new SharedChatMessageDto(entity.getId(), new MemberDto(member.getId(), member.getName(),
                member.getInitial(), member.getColor(), member.getRole()), entity.getContent(), entity.getImageId(),
                entity.getImageName(), StringUtils.hasText(entity.getImageId())
                        ? "/api/images/" + entity.getImageId() + "/raw" : null,
                entity.getFeishuStatus(), entity.getFeishuError(), entity.getCreatedAt());
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

    private static String markdownSafe(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("<", "＜").replace(">", "＞")
                .replace("[", "\\[").replace("]", "\\]");
    }

    private static MemberEntity unknownMember(String id) {
        MemberEntity member = new MemberEntity();
        member.setId(id);
        member.setName("已删除成员");
        member.setInitial("?");
        member.setColor("#9A9DA9");
        member.setRole("");
        return member;
    }
}
