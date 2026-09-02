package cn.wayuwa.wao.service;

import cn.wayuwa.wao.domain.FeishuTextPolishRunEntity;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMention;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.FeishuTextPolishRunMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 从飞书 @润色机器人 消息创建持久 AI 运行，并把真实结果写回群聊与成员历史。 */
@Service
public class FeishuTextPolishService {
    private static final Logger log = LoggerFactory.getLogger(FeishuTextPolishService.class);
    private static final int WEBHOOK_TEXT_BYTES = 24_000;
    private static final int ERROR_LIMIT = 200;

    private final FeishuTextPolishConfigService config;
    private final FeishuMemberIdentityService memberIdentities;
    private final FeishuTextPolishRunMapper runs;
    private final TextPolishService textPolish;
    private final FeishuTextPolishAiQueue aiQueue;
    private final FeishuTextPolishCompletionService completion;
    private final AiAssistantService assistant;
    private final FeishuWebhookNotifier notifier;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final ObjectMapper json;

    public FeishuTextPolishService(FeishuTextPolishConfigService config,
            FeishuMemberIdentityService memberIdentities, FeishuTextPolishRunMapper runs,
            TextPolishService textPolish, FeishuTextPolishAiQueue aiQueue,
            FeishuTextPolishCompletionService completion, AiAssistantService assistant,
            FeishuWebhookNotifier notifier, SettingService settings, FeishuOpenApiClient feishu,
            ObjectMapper json) {
        this.config = config;
        this.memberIdentities = memberIdentities;
        this.runs = runs;
        this.textPolish = textPolish;
        this.aiQueue = aiQueue;
        this.completion = completion;
        this.assistant = assistant;
        this.notifier = notifier;
        this.settings = settings;
        this.feishu = feishu;
        this.json = json;
    }

    /** 当前功能是否开启，供共享群消息轮询器决定是否访问飞书。 */
    public boolean enabled() {
        return config.enabled();
    }

    /** 处理一条主动轮询读到的消息；重复 message_id 不会再次入队或回复。 */
    public void collectPolled(String chatId, FeishuMessage source) {
        String mentionName = config.mentionName();
        if (!eligible(source, mentionName)) return;
        String configuredChat = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (StringUtils.hasText(configuredChat) && !configuredChat.equals(chatId)) return;

        FeishuTextPolishRunEntity run = new FeishuTextPolishRunEntity();
        run.setId(newId("fp"));
        run.setRecordId(newId("tp"));
        run.setMessageId(source.messageId());
        run.setChatId(chatId);
        run.setSenderOpenId(source.senderId());
        run.setWebhookId(config.webhookId());
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(run.getCreatedAt());

        String failure = null;
        try {
            String original = readableText(source, mentionName);
            run.setOriginalText(original);
            String memberId = memberIdentities.resolvePolledMember(chatId, source.senderId());
            run.setMemberId(memberId);
            if (!StringUtils.hasText(memberId)) {
                failure = "请先使用同一飞书账号登录 WAO 完成成员绑定";
            } else if (!StringUtils.hasText(run.getWebhookId())) {
                failure = "群聊润色尚未选择可用的 Webhook";
            } else {
                TextPolishService.PreparedPolish prepared = textPolish.prepare(memberId, original);
                run.setOriginalText(prepared.originalText());
                run.setPromptSnapshot(prepared.promptSnapshot());
            }
        } catch (RuntimeException exception) {
            failure = safeError(exception);
        }
        run.setStatus(failure == null ? "QUEUED" : "FAILED");
        run.setErrorMessage(failure);
        try {
            runs.insert(run);
        } catch (DuplicateKeyException duplicate) {
            return;
        }
        if (failure != null) {
            notifyFailure(run, failure);
            react(run.getMessageId(), "ERROR");
        }
    }

    /** 推进一步等待中的持久运行；一次最多处理当前最老的八条。 */
    public void advance() {
        if (!enabled()) return;
        LocalDateTime now = LocalDateTime.now();
        runs.recoverStaleRecording(now.minusMinutes(5), now);
        runs.failStaleSending(now.minusMinutes(5), now);
        for (FeishuTextPolishRunEntity run : runs.findAdvanceable()) {
            try {
                switch (run.getStatus()) {
                    case "QUEUED" -> aiQueue.enqueue(run.getId());
                    case "AI_PENDING" -> pollAi(run);
                    case "GENERATED" -> deliver(run);
                    case "DELIVERED" -> finish(run);
                    default -> { }
                }
            } catch (RuntimeException exception) {
                fail(run, safeError(exception));
            }
        }
    }

    private void pollAi(FeishuTextPolishRunEntity run) {
        AiAssistantService.ReadOnlyJobResult result = assistant.readOnlyJobResult(
                run.getId(), run.getAiRunId());
        if (result.active()) return;
        if (!"succeeded".equals(result.status()) || !StringUtils.hasText(result.response())) {
            fail(run, StringUtils.hasText(result.error()) ? result.error() : "AI 没有返回有效润色结果");
            return;
        }
        runs.markGenerated(run.getId(), result.response().trim(), LocalDateTime.now());
    }

    private void deliver(FeishuTextPolishRunEntity run) {
        if (runs.claimGenerated(run.getId(), LocalDateTime.now()) != 1) return;
        for (String message : resultMessages(run.getPolishedText())) {
            notifier.sendText(run.getWebhookId(), message);
        }
        runs.markDelivered(run.getId(), LocalDateTime.now());
    }

    private void finish(FeishuTextPolishRunEntity run) {
        if (completion.complete(run.getId())) {
            react(run.getMessageId(), "DONE");
        }
    }

    private void fail(FeishuTextPolishRunEntity run, String error) {
        if (runs.markFailed(run.getId(), abbreviated(error), LocalDateTime.now()) != 1) return;
        notifyFailure(run, error);
        react(run.getMessageId(), "ERROR");
    }

    private void notifyFailure(FeishuTextPolishRunEntity run, String error) {
        if (!StringUtils.hasText(run.getWebhookId())) return;
        try {
            notifier.sendText(run.getWebhookId(), "润色失败：" + abbreviated(error));
        } catch (RuntimeException exception) {
            log.warn("飞书润色失败回执未发出（Webhook 与错误详情已隐藏）");
        }
    }

    private void react(String messageId, String emojiType) {
        if (!StringUtils.hasText(messageId)) return;
        try {
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (StringUtils.hasText(appId) && StringUtils.hasText(appSecret)) {
                feishu.addMessageReaction(feishu.tenantAccessToken(appId, appSecret), messageId, emojiType);
            }
        } catch (RuntimeException exception) {
            log.warn("飞书润色表情回执失败（凭据与消息标识已隐藏）");
        }
    }

    static boolean eligible(FeishuMessage message, String mentionName) {
        return message != null && "user".equals(message.senderType()) && !message.deleted()
                && StringUtils.hasText(message.messageId())
                && List.of("text", "post").contains(message.msgType())
                && message.mentions().stream().anyMatch(mention -> mentionName.equals(mention.name()));
    }

    String readableText(FeishuMessage message, String mentionName) {
        JsonNode content = read(message.content());
        if (content.has("zh_cn") && content.path("zh_cn").isObject()) content = content.path("zh_cn");
        String text = "post".equals(message.msgType()) ? postText(content, mentionName)
                : content.path("text").asText("");
        for (FeishuMention mention : message.mentions()) {
            String replacement = mentionName.equals(mention.name()) ? "" : "@" + mention.name();
            if (StringUtils.hasText(mention.key())) text = text.replace(mention.key(), replacement);
        }
        return text.replaceAll("[ \\t]+\\n", "\n").trim();
    }

    private static String postText(JsonNode content, String mentionName) {
        StringBuilder out = new StringBuilder();
        if (StringUtils.hasText(content.path("title").asText(""))) {
            out.append(content.path("title").asText()).append('\n');
        }
        for (JsonNode line : content.path("content")) {
            for (JsonNode span : line) {
                switch (span.path("tag").asText("")) {
                    case "text" -> out.append(span.path("text").asText(""));
                    case "a" -> {
                        out.append(span.path("text").asText(""));
                        String href = span.path("href").asText("");
                        if (StringUtils.hasText(href)) out.append(' ').append(href);
                    }
                    case "at" -> {
                        String name = span.path("user_name").asText(span.path("name").asText(""));
                        if (StringUtils.hasText(name) && !mentionName.equals(name)) out.append('@').append(name);
                    }
                    default -> { }
                }
            }
            out.append('\n');
        }
        return out.toString();
    }

    static List<String> resultMessages(String polishedText) {
        String one = "润色结果：\n" + polishedText;
        if (utf8Length(one) <= WEBHOOK_TEXT_BYTES) return List.of(one);
        List<String> bodies = splitUtf8(polishedText, WEBHOOK_TEXT_BYTES - 128);
        List<String> messages = new ArrayList<>();
        for (int index = 0; index < bodies.size(); index++) {
            messages.add("润色结果（" + (index + 1) + "/" + bodies.size() + "）：\n" + bodies.get(index));
        }
        return List.copyOf(messages);
    }

    private static List<String> splitUtf8(String text, int maxBytes) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            String value = new String(Character.toChars(codePoint));
            int size = utf8Length(value);
            if (bytes + size > maxBytes && !current.isEmpty()) {
                parts.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            current.append(value);
            bytes += size;
            offset += Character.charCount(codePoint);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(StringUtils.hasText(value) ? value : "{}");
        } catch (Exception exception) {
            return json.createObjectNode();
        }
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String newId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String safeError(RuntimeException exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "润色处理失败，请重新发送原消息";
    }

    private static String abbreviated(String value) {
        String text = StringUtils.hasText(value) ? value.trim() : "润色处理失败，请重新发送原消息";
        return text.length() <= ERROR_LIMIT ? text : text.substring(0, ERROR_LIMIT);
    }
}
