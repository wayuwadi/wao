package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.domain.SharedChatMessageEntity;
import cn.wayuwa.wao.domain.SharedChatReactionEntity;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.dto.SaveSharedChatConfigRequest;
import cn.wayuwa.wao.dto.SharedChatConfigDto;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 共享聊天的飞书自建应用桥接：普通富文本出站、群历史入站、官方已读与表情事件。
 * 任何飞书失败都只改变桥接状态，不回滚 WAO 已保存消息。
 */
@Service
public class SharedChatFeishuBridge {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Set<String> EMOJIS = Set.of("THUMBSUP", "HEART", "SMILE", "DONE");
    private static final Pattern LOCAL_IMAGE = Pattern.compile(
            "!\\[([^]]*)]\\((?:https?://[^/]+)?/api/images/([A-Za-z0-9_-]+)/raw(?:\\s+\"[^\"]*\")?\\)");
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 15;

    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final SharedChatMessageMapper messages;
    private final SharedChatMessageReadMapper reads;
    private final SharedChatReactionMapper reactions;
    private final ImageBlobMapper imageBlobs;
    private final ImageService imageService;
    private final MemberMapper members;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final ObjectMapper json;
    private final Map<String, String> senderMembers = new ConcurrentHashMap<>();

    public SharedChatFeishuBridge(SettingService settings, FeishuOpenApiClient feishu,
            SharedChatMessageMapper messages, SharedChatMessageReadMapper reads,
            SharedChatReactionMapper reactions, ImageBlobMapper imageBlobs, ImageService imageService,
            MemberMapper members, AccountMapper accounts, AccountIdentityMapper identities, ObjectMapper json) {
        this.settings = settings;
        this.feishu = feishu;
        this.messages = messages;
        this.reads = reads;
        this.reactions = reactions;
        this.imageBlobs = imageBlobs;
        this.imageService = imageService;
        this.members = members;
        this.accounts = accounts;
        this.identities = identities;
        this.json = json;
    }

    /** 当前桥接目标，不把 App Secret 暴露给页面。 */
    public SharedChatConfigDto config() {
        String id = settings.get(SettingService.SHARED_CHAT_CHAT_ID, "");
        String name = settings.get(SettingService.SHARED_CHAT_CHAT_NAME, "");
        return new SharedChatConfigDto(id, name, StringUtils.hasText(id), pollIntervalSeconds());
    }

    /** 机器人真实所在群，未配置自建应用时返回明确错误。 */
    public List<FeishuChat> chats() {
        return feishu.listChats(token());
    }

    /** 只允许保存当前应用确实加入的群，空 id 明确关闭桥接。 */
    @Transactional
    public SharedChatConfigDto saveConfig(SaveSharedChatConfigRequest request) {
        String id = request == null || request.chatId() == null ? "" : request.chatId().trim();
        int interval = request == null || request.pollIntervalSeconds() == null
                ? pollIntervalSeconds() : request.pollIntervalSeconds();
        if (interval < 5 || interval > 3_600) {
            throw new BadRequestException("聊天同步频率必须在 5 秒到 60 分钟之间");
        }
        settings.put(SettingService.SHARED_CHAT_POLL_INTERVAL_SECONDS, String.valueOf(interval));
        if (id.isEmpty()) {
            settings.put(SettingService.SHARED_CHAT_CHAT_ID, "");
            settings.put(SettingService.SHARED_CHAT_CHAT_NAME, "");
            return config();
        }
        FeishuChat selected = chats().stream().filter(chat -> id.equals(chat.chatId())).findFirst()
                .orElseThrow(() -> new BadRequestException("所选群不在当前飞书应用的真实群列表中"));
        settings.put(SettingService.SHARED_CHAT_CHAT_ID, selected.chatId());
        settings.put(SettingService.SHARED_CHAT_CHAT_NAME, selected.name());
        return config();
    }

    /** 返回已经校验的动态消息轮询秒数；损坏旧值安全回退到 15 秒。 */
    public int pollIntervalSeconds() {
        try {
            int value = Integer.parseInt(settings.get(
                    SettingService.SHARED_CHAT_POLL_INTERVAL_SECONDS,
                    String.valueOf(DEFAULT_POLL_INTERVAL_SECONDS)));
            return value >= 5 && value <= 3_600 ? value : DEFAULT_POLL_INTERVAL_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_POLL_INTERVAL_SECONDS;
        }
    }

    /** 把已落库 WAO 消息投递成普通飞书图文或原生引用回复。 */
    public void deliver(SharedChatMessageEntity entity, MemberEntity member) {
        SharedChatConfigDto target = config();
        if (!target.configured()) {
            updateDelivery(entity, "UNAVAILABLE", "请先选择要同步的飞书群");
            return;
        }
        try {
            String token = token();
            Object post = postContent(token, member.getName(), entity);
            SharedChatMessageEntity parent = StringUtils.hasText(entity.getReplyToId())
                    ? messages.selectById(entity.getReplyToId()) : null;
            String parentFeishuId = parent == null ? null : parent.getFeishuMessageId();
            String messageId = feishu.sendPostMessage(token, target.chatId(), post, entity.getId(), parentFeishuId);
            entity.setFeishuChatId(target.chatId());
            entity.setFeishuMessageId(messageId);
            updateDelivery(entity, "SENT", null);
        } catch (RuntimeException exception) {
            updateDelivery(entity, "FAILED", "同步失败，请稍后重试");
        }
    }

    /** 轮询到的一条用户群消息按 message_id 幂等写入；编辑与撤回覆盖同一条。 */
    public ImportResult importMessage(String token, String chatId, String chatName, FeishuMessage source,
            Map<String, String> senderNames) {
        if (source == null || !StringUtils.hasText(source.messageId())) return ImportResult.skipped();
        SharedChatMessageEntity existing = messages.findByFeishuMessageId(source.messageId());
        // 当前应用发出的 WAO 消息已经由 deliver 绑定；其它应用消息不冒充成员聊天。
        if (!"user".equals(source.senderType())) return ImportResult.skipped();
        String feishuSenderName = senderNames.getOrDefault(source.senderId(), "飞书成员");
        String fingerprint = sourceHash(source, feishuSenderName);
        if (existing != null && "FEISHU".equals(existing.getSource())
                && fingerprint.equals(existing.getFeishuSourceHash())) {
            return ImportResult.unchanged();
        }

        String memberId = memberId(source.senderId(), chatId, token);
        MemberEntity member = StringUtils.hasText(memberId) ? members.selectById(memberId) : null;
        String senderName = member != null ? member.getName() : feishuSenderName;
        RenderedIncoming rendered = renderIncoming(token, chatName, source);
        SharedChatMessageEntity parent = StringUtils.hasText(source.parentId())
                ? messages.findByFeishuMessageId(source.parentId()) : null;
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        if (existing != null) {
            if (!"FEISHU".equals(existing.getSource())) return ImportResult.skipped();
            int oldContentBytes = utf8Bytes(existing.getContent());
            existing.setContent(rendered.content());
            existing.setImageId(rendered.imageId());
            existing.setImageName(rendered.imageName());
            existing.setExternalSenderName(senderName);
            existing.setReplyToId(parent == null ? null : parent.getId());
            existing.setDeleted(source.deleted());
            existing.setFeishuSourceHash(fingerprint);
            existing.setUpdatedAt(now);
            messages.updateById(existing);
            return new ImportResult(0, 1, rendered.imageBytes()
                    + Math.max(0, utf8Bytes(rendered.content()) - oldContentBytes));
        }
        SharedChatMessageEntity entity = new SharedChatMessageEntity();
        entity.setId(uuid());
        entity.setMemberId(memberId);
        entity.setSource("FEISHU");
        entity.setExternalSenderId(source.senderId());
        entity.setExternalSenderName(senderName);
        entity.setContent(rendered.content());
        entity.setImageId(rendered.imageId());
        entity.setImageName(rendered.imageName());
        entity.setFeishuChatId(chatId);
        entity.setFeishuMessageId(source.messageId());
        entity.setFeishuSourceHash(fingerprint);
        entity.setReplyToId(parent == null ? null : parent.getId());
        entity.setDeleted(source.deleted());
        entity.setFeishuStatus("RECEIVED");
        LocalDateTime created = source.createTime() > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(source.createTime()), SHANGHAI) : now;
        entity.setCreatedAt(created);
        entity.setUpdatedAt(now);
        try {
            messages.insert(entity);
        } catch (DuplicateKeyException ignored) {
            // Linux / Windows 节点同时轮询时由 feishu_message_id 唯一键选出唯一赢家。
            return ImportResult.unchanged();
        }
        return new ImportResult(1, 0, rendered.imageBytes() + utf8Bytes(rendered.content()));
    }

    /** 处理官方已读或表情事件；返回 true 表示事件属于共享聊天并已收下。 */
    public boolean handleEvent(JsonNode body) {
        String type = body.path("header").path("event_type").asText("");
        JsonNode event = body.path("event");
        if ("im.message.message_read_v1".equals(type)) {
            JsonNode reader = event.path("reader");
            Identity identity = identity(reader.path("reader_id"));
            LocalDateTime at = eventTime(reader.path("read_time").asText(""));
            for (JsonNode id : event.path("message_id_list")) {
                SharedChatMessageEntity message = messages.findByFeishuMessageId(id.asText(""));
                if (message != null) reads.insertIgnore(uuid(), message.getId(), identity.key(),
                        identity.memberId(), "FEISHU", at);
            }
            return true;
        }
        boolean created = "im.message.reaction.created_v1".equals(type);
        boolean deleted = "im.message.reaction.deleted_v1".equals(type);
        if (!created && !deleted) return false;
        SharedChatMessageEntity message = messages.findByFeishuMessageId(event.path("message_id").asText(""));
        if (message == null || !"user".equals(event.path("operator_type").asText(""))) return true;
        String emoji = event.path("reaction_type").path("emoji_type").asText("");
        if (!EMOJIS.contains(emoji)) return true;
        Identity identity = identity(event.path("user_id"));
        if (deleted) {
            reactions.deleteReaction(message.getId(), identity.key(), emoji);
        } else if (reactions.find(message.getId(), identity.key(), emoji) == null) {
            insertReaction(message.getId(), identity, emoji, "FEISHU", null,
                    eventTime(event.path("action_time").asText("")));
        }
        return true;
    }

    /** 当前 WAO 成员切换表情；有飞书消息 id 时先完成真实飞书操作再写本地状态。 */
    public void toggleReaction(String memberId, String messageId, String emojiType) {
        if (!EMOJIS.contains(emojiType)) throw new BadRequestException("不支持该表情");
        SharedChatMessageEntity message = messages.selectById(messageId);
        if (message == null) throw new BadRequestException("聊天消息不存在");
        MemberEntity member = members.selectById(memberId);
        if (member == null) throw new BadRequestException("当前成员不存在");
        String key = "member:" + memberId;
        SharedChatReactionEntity existing = reactions.find(messageId, key, emojiType);
        if (existing != null) {
            if (StringUtils.hasText(existing.getFeishuReactionId()) && StringUtils.hasText(message.getFeishuMessageId())) {
                feishu.deleteMessageReaction(token(), message.getFeishuMessageId(), existing.getFeishuReactionId());
            }
            reactions.deleteReaction(messageId, key, emojiType);
            return;
        }
        String reactionId = StringUtils.hasText(message.getFeishuMessageId())
                ? feishu.addMessageReaction(token(), message.getFeishuMessageId(), emojiType) : null;
        insertReaction(messageId, new Identity(key, memberId, member.getName()), emojiType,
                "WAO", reactionId, LocalDateTime.now(SHANGHAI));
    }

    private Object postContent(String token, String memberName, SharedChatMessageEntity entity) {
        List<List<Map<String, Object>>> lines = new ArrayList<>();
        lines.add(List.of(Map.of("tag", "text", "text", memberName + "：")));
        for (String rawLine : entity.getContent().split("\\R", -1)) {
            List<Map<String, Object>> spans = new ArrayList<>();
            Matcher matcher = LOCAL_IMAGE.matcher(rawLine);
            int offset = 0;
            while (matcher.find()) {
                if (matcher.start() > offset) spans.add(textSpan(rawLine.substring(offset, matcher.start())));
                ImageBlobEntity image = imageBlobs.selectById(matcher.group(2));
                if (image != null && image.getContent() != null) {
                    spans.add(Map.of("tag", "img", "image_key", feishu.uploadMessageImage(token,
                            image.getContent(), image.getContentType(), image.getName())));
                }
                offset = matcher.end();
            }
            if (offset < rawLine.length()) spans.add(textSpan(rawLine.substring(offset)));
            if (spans.isEmpty()) spans.add(textSpan(" "));
            lines.add(spans);
        }
        if (StringUtils.hasText(entity.getImageId()) && !entity.getContent().contains(entity.getImageId())) {
            ImageBlobEntity image = imageBlobs.selectById(entity.getImageId());
            if (image != null && image.getContent() != null) {
                lines.add(List.of(Map.of("tag", "img", "image_key", feishu.uploadMessageImage(token,
                        image.getContent(), image.getContentType(), image.getName()))));
            }
        }
        return Map.of("zh_cn", Map.of("title", "", "content", lines));
    }

    private RenderedIncoming renderIncoming(String token, String chatName, FeishuMessage message) {
        if (message.deleted()) return new RenderedIncoming("[消息已撤回]", null, null, 0);
        JsonNode content = read(message.content());
        if (content.has("zh_cn")) content = content.path("zh_cn");
        StringBuilder text = new StringBuilder();
        long imageBytes = 0;
        switch (message.msgType()) {
            case "text" -> text.append(content.path("text").asText(""));
            case "image" -> imageBytes += appendIncomingImage(text, token, chatName, message,
                    content.path("image_key").asText(""));
            case "post" -> {
                if (StringUtils.hasText(content.path("title").asText(""))) {
                    text.append("**").append(content.path("title").asText()).append("**\n\n");
                }
                for (JsonNode line : content.path("content")) {
                    for (JsonNode span : line) {
                        switch (span.path("tag").asText("")) {
                            case "text" -> text.append(span.path("text").asText(""));
                            case "a" -> text.append('[').append(span.path("text").asText(""))
                                    .append("](").append(span.path("href").asText("")).append(')');
                            case "at" -> text.append('@').append(span.path("user_name").asText(""));
                            case "img" -> imageBytes += appendIncomingImage(text, token, chatName, message,
                                    span.path("image_key").asText(""));
                            default -> { }
                        }
                    }
                    text.append('\n');
                }
            }
            case "file" -> text.append("[文件] ").append(content.path("file_name").asText(""));
            case "audio" -> text.append("[语音]");
            case "media" -> text.append("[视频] ").append(content.path("file_name").asText(""));
            case "sticker" -> text.append("[表情]");
            default -> text.append('[').append(message.msgType()).append(']');
        }
        return new RenderedIncoming(text.toString().trim(), null, null, imageBytes);
    }

    private long appendIncomingImage(StringBuilder text, String token, String chatName,
            FeishuMessage message, String imageKey) {
        if (!StringUtils.hasText(imageKey)) return 0;
        byte[] bytes = feishu.downloadImage(token, message.messageId(), imageKey);
        if (bytes == null || bytes.length == 0) return 0;
        ImageItemDto image = imageService.store(bytes, "image/png", imageKey + ".png",
                "飞书聊天 · " + chatName, true);
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append("\n\n");
        text.append("![飞书图片](").append(image.url()).append(')');
        return bytes.length;
    }

    private void insertReaction(String messageId, Identity identity, String emoji, String source,
            String reactionId, LocalDateTime at) {
        SharedChatReactionEntity reaction = new SharedChatReactionEntity();
        reaction.setId(uuid());
        reaction.setMessageId(messageId);
        reaction.setReactorKey(identity.key());
        reaction.setMemberId(identity.memberId());
        reaction.setReactorName(identity.name());
        reaction.setEmojiType(emoji);
        reaction.setSource(source);
        reaction.setFeishuReactionId(reactionId);
        reaction.setCreatedAt(at);
        try {
            reactions.insert(reaction);
        } catch (DuplicateKeyException ignored) {
            // 事件重推或双节点同时消费时保持同一个表情事实。
        }
    }

    private Identity identity(JsonNode ids) {
        String open = ids.path("open_id").asText("");
        String union = ids.path("union_id").asText("");
        String user = ids.path("user_id").asText("");
        AccountIdentityEntity accountIdentity = StringUtils.hasText(open)
                ? identities.findByProviderSubject("feishu", open) : null;
        if (accountIdentity == null && StringUtils.hasText(union)) {
            accountIdentity = identities.findByProviderUnionSubject("feishu", union);
        }
        String memberId = memberId(accountIdentity);
        MemberEntity member = StringUtils.hasText(memberId) ? members.selectById(memberId) : null;
        String stable = StringUtils.hasText(union) ? union : StringUtils.hasText(open) ? open : user;
        return member != null
                ? new Identity("member:" + memberId, memberId, member.getName())
                : new Identity("feishu:" + stable, null, "飞书成员");
    }

    private String memberId(String openId, String chatId, String token) {
        if (!StringUtils.hasText(openId)) return null;
        String cached = senderMembers.get(openId);
        if (cached != null) return cached.isEmpty() ? null : cached;
        AccountIdentityEntity identity = identities.findByProviderSubject("feishu", openId);
        String memberId = memberId(identity);
        if (!StringUtils.hasText(memberId)) {
            try {
                String union = feishu.chatMemberUnionId(token, chatId, openId);
                if (StringUtils.hasText(union)) {
                    memberId = memberId(identities.findByProviderUnionSubject("feishu", union));
                }
            } catch (RuntimeException ignored) {
                // 名称不唯一或群成员权限不足时保持外部成员身份，不猜归属。
            }
        }
        senderMembers.put(openId, memberId == null ? "" : memberId);
        return memberId;
    }

    private String memberId(AccountIdentityEntity identity) {
        if (identity == null) return null;
        AccountEntity account = accounts.selectById(identity.getAccountId());
        return account == null ? null : account.getMemberId();
    }

    private String token() {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(secret)) {
            throw new BadRequestException("请先在飞书群聊同步中配置自建应用 App ID 与 App Secret");
        }
        return feishu.tenantAccessToken(appId, secret);
    }

    private void updateDelivery(SharedChatMessageEntity entity, String status, String error) {
        entity.setFeishuStatus(status);
        entity.setFeishuError(error);
        entity.setUpdatedAt(LocalDateTime.now(SHANGHAI));
        messages.updateById(entity);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(StringUtils.hasText(value) ? value : "{}");
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
    }

    private static Map<String, Object> textSpan(String text) {
        return Map.of("tag", "text", "text", text);
    }

    private static LocalDateTime eventTime(String millis) {
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(millis)), SHANGHAI);
        } catch (RuntimeException ignored) {
            return LocalDateTime.now(SHANGHAI);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String sourceHash(FeishuMessage source, String senderName) {
        String value = String.join("\u001f", source.senderId(), source.senderType(), source.msgType(),
                source.content(), String.valueOf(source.deleted()), source.rootId(), source.parentId(), senderName);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 单条飞书消息对持久化内容造成的真实可归因增量。 */
    public record ImportResult(int inserted, int updated, long bytesAdded) {
        static ImportResult skipped() { return new ImportResult(0, 0, 0); }
        static ImportResult unchanged() { return new ImportResult(0, 0, 0); }
    }
    private record RenderedIncoming(String content, String imageId, String imageName, long imageBytes) { }
    private record Identity(String key, String memberId, String name) { }
}
