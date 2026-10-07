package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.dto.FeishuQuickCollectConfigDto;
import cn.wayuwa.wao.dto.CreateNoteRequest;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.ProjectDto;
import cn.wayuwa.wao.dto.SaveQuickCollectConfigRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessageReaction;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.FeishuQuickCollectEventMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * 群聊快速收集：@任务机器人创建个人待办，@小记机器人创建本人小记。
 *
 * <p>存在的理由：想法是在聊天里冒出来的，稍纵即逝。打开网站、找项目、点新建、填表单这一串
 * 动作足以让人放弃记录，于是想法就丢了。@ 一下机器人是聊天里本来就会做的动作，成本近似为零。
 * 手机上还能直接用飞书自带的语音转文字说完就发 —— 所以这里只处理已经变成文字的消息，
 * 收到语音条会回一句提示，而不是假装能转写。
 *
 * <p>安全上不额外造轮子：飞书事件订阅本身提供 Encrypt Key（AES-256-CBC）和 Verification Token，
 * 配了就校验、就解密；两者都没配时只接受来自已配置群的消息。凭据和其他飞书配置一样加密存库。
 */
@Service
public class FeishuQuickCollectService {

    /** 打在任务上的来源标签。 */
    public static final String SOURCE_TAG = "群聊快速收集";
    /** “记录想法”仍是个人待办，但保留来源语义。 */
    public static final String IDEA_SOURCE_TAG = "群聊想法收集";
    /** 默认落点项目名（用户指定的第一个项目）。 */
    private static final String DEFAULT_PROJECT_NAME = "Wa All in One";
    /** 标题最多取多少字，超出的部分留在描述里。 */
    private static final int TITLE_LIMIT = 40;
    /** 纯图片消息没有可诚实提取的文字时使用的稳定标题。 */
    private static final String IMAGE_ONLY_TITLE = "图片待办";
    private static final String TASK_BOT_NAME = "任务待办";
    private static final String NOTE_BOT_NAME = "小记创作";
    /** 飞书富文本偶尔把编辑器 HTML 放进 text 节点；标题必须只保留用户看见的文字。 */
    private static final Pattern HTML_BLOCK_BOUNDARY = Pattern.compile(
            "(?i)<\\s*/?\\s*(?:p|div|h[1-6]|li|ul|ol|blockquote)\\b[^>]*>|<\\s*br\\s*/?\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("(?s)<\\s*/?\\s*[a-zA-Z][^>]*>");
    private static final String MESSAGE_RECALLED_EVENT = "im.message.recalled_v1";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PUBLIC_BASE_URL = "https://wao.wayuwa.cn";
    private static final long REACTION_CHECK_TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final int MAX_RECENT_REACTION_CHECKS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(FeishuQuickCollectService.class);

    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final TaskService tasks;
    private final KnowledgeService knowledge;
    private final ProjectService projects;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final FeishuQuickCollectEventMapper events;
    private final ImageService images;
    private final ObjectMapper json;
    private final NotificationFeedbackService feedback;
    private final FeishuQuickCollectAiRunService reactionAiRuns;
    private final FeishuQuickCollectReactionPolicy reactionPolicy;
    private final FeishuQuickCollectRecallService recalls;
    private final FeishuLinkPreviewService linkPreviews;
    private final Map<String, Long> recentReactionChecks = new ConcurrentHashMap<>();
    private FeishuWebhookConfigService botRegistry;
    private FeishuBotInboxService inbox;
    private FeishuCaptureTargetGuard targetGuard;

    /** 生产入口使用持久收件箱、显式机器人路由和来源版本保护。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void setBotCenter(FeishuWebhookConfigService botRegistry, FeishuBotInboxService inbox,
            FeishuCaptureTargetGuard targetGuard) {
        this.botRegistry = botRegistry; this.inbox = inbox; this.targetGuard = targetGuard;
    }
    public FeishuQuickCollectService(SettingService settings, FeishuOpenApiClient feishu,
            TaskService tasks, KnowledgeService knowledge, ProjectService projects, AccountMapper accounts,
            AccountIdentityMapper identities, FeishuQuickCollectEventMapper events, ImageService images,
            ObjectMapper json, NotificationFeedbackService feedback,
            FeishuQuickCollectAiRunService reactionAiRuns, FeishuQuickCollectReactionPolicy reactionPolicy,
            FeishuQuickCollectRecallService recalls, FeishuLinkPreviewService linkPreviews) {
        this.settings = settings;
        this.feishu = feishu;
        this.tasks = tasks;
        this.knowledge = knowledge;
        this.projects = projects;
        this.accounts = accounts;
        this.identities = identities;
        this.events = events;
        this.images = images;
        this.json = json;
        this.feedback = feedback;
        this.reactionAiRuns = reactionAiRuns;
        this.reactionPolicy = reactionPolicy;
        this.recalls = recalls;
        this.linkPreviews = linkPreviews;
    }

    /** 当前配置（Token / Encrypt Key 只回脱敏形态）。 */
    public FeishuQuickCollectConfigDto config() {
        String token = settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN);
        String key = settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY);
        return new FeishuQuickCollectConfigDto(
                settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED),
                settings.get(SettingService.QUICK_COLLECT_PROJECT_ID, ""),
                SettingService.mask(token), StringUtils.hasText(token),
                SettingService.mask(key), StringUtils.hasText(key),
                mentionOpenIds(), reactionPolicy.receivedEmoji(), reactionPolicy.triggerEmoji(),
                reactionPolicy.completedEmoji());
    }

    /**
     * 保存配置。密文字段传 null 表示保持原值（前端密码框留空即不改）。
     *
     * @param req 配置表单
     * @return 保存后的配置（脱敏）
     */
    @Transactional
    public FeishuQuickCollectConfigDto saveConfig(SaveQuickCollectConfigRequest req) {
        settings.put(SettingService.QUICK_COLLECT_ENABLED,
                req.enabled() == null ? null : String.valueOf(req.enabled()));
        settings.put(SettingService.QUICK_COLLECT_PROJECT_ID, trimmed(req.projectId()));
        settings.put(SettingService.QUICK_COLLECT_VERIFY_TOKEN, trimmed(req.verificationToken()));
        settings.put(SettingService.QUICK_COLLECT_ENCRYPT_KEY, trimmed(req.encryptKey()));
        if (req.mentionOpenIds() != null) {
            settings.put(SettingService.QUICK_COLLECT_MENTIONS, String.join(",", req.mentionOpenIds().stream()
                    .filter(StringUtils::hasText).map(String::trim).distinct().toList()));
        }
        reactionPolicy.save(req.receivedEmoji(), req.triggerEmoji(), req.completedEmoji());
        return config();
    }

    /**
     * 处理一次飞书事件回调。
     *
     * <p>飞书要求 3 秒内返回 200，否则会重推；因此这里只做「解密 → 认领 → 建任务」，
     * 任何异常都吞掉并记日志，不让飞书拿到 5xx 之后无限重推同一条消息。
     *
     * @param rawBody 原始请求体
     * @return 要回给飞书的响应体（URL 校验时是 challenge，其余情况是空对象）
     */
    public Map<String, Object> handleEvent(String rawBody) {
        return handleEvent(rawBody, PUBLIC_BASE_URL);
    }

    /**
     * 处理一次飞书事件回调，并把当前站点根地址交给表情派工链路生成真实任务分享地址。
     *
     * @param rawBody 原始请求体
     * @param baseUrl 当前请求对应的 WAO 根地址
     * @return 飞书协议响应
     */
    public Map<String, Object> handleEvent(String rawBody, String baseUrl) {
        boolean encryptedEnvelope = StringUtils.hasText(read(rawBody).path("encrypt").asText(""));
        JsonNode body = decrypt(rawBody);
        if ("url_verification".equals(body.path("type").asText(""))) {
            requireToken(body.path("token").asText(""), encryptedEnvelope);
            return Map.of("challenge", body.path("challenge").asText(""));
        }
        requireToken(body.path("header").path("token").asText(body.path("token").asText("")), encryptedEnvelope);
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        if (!StringUtils.hasText(appId) || !appId.equals(body.path("header").path("app_id").asText("")))
            throw new BadRequestException("飞书事件应用与接收配置不一致");
        String eventType = body.path("header").path("event_type").asText("");
        if ("url.preview.get".equals(eventType)) {
            return linkPreviews.preview(body.path("event"), baseUrl);
        }
        if ("card.action.trigger".equals(eventType)) {
            JsonNode event = body.path("event");
            var result = feedback.collect(event);
            if (result == null) {
                return Map.of("toast", Map.of("type", "warning", "content", "反馈未记录，请稍后重试"));
            }
            if (!result.displayed()) {
                return Map.of("toast", Map.of("type", "warning", "content",
                        "已记录：" + result.outcome() + "；" + result.hint()));
            }
            return Map.of("toast", Map.of("type", "success", "content",
                    "已记录：" + result.outcome() + "，已用你的身份贴表情"));
        }
        if (MESSAGE_RECALLED_EVENT.equals(eventType)) {
            try {
                recall(body.path("event"), body.path("header").path("event_id").asText(""));
            } catch (RuntimeException ex) {
                // 回调仍按既有协议返回 200；一分钟回看会用 deleted=true 的真实消息状态再次收敛。
                log.warn("飞书原消息撤回同步失败：{}", ex.getClass().getSimpleName());
            }
            return Map.of();
        }
        boolean quickCollectEnabled = settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED);
        boolean encryptedReactionChannel = encryptedEnvelope
                && StringUtils.hasText(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY));
        boolean verifiedReactionChannel = StringUtils.hasText(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN))
                || encryptedReactionChannel;
        boolean reactionTriggered = false;
        if (quickCollectEnabled && verifiedReactionChannel) {
            try {
                reactionTriggered = reactionAiRuns.trigger(body, baseUrl);
            } catch (RuntimeException ex) {
                // 回调必须保持 200；事务会回滚，用户重新添加表情即可再派工，并已尽力在原消息加 ERROR。
                log.warn("飞书触发表情启动 AI Run 失败：{}", ex.getClass().getSimpleName());
                reactionTriggered = true;
            }
        }
        if (reactionTriggered) {
            return Map.of();
        }
        if (!quickCollectEnabled) {
            return Map.of();
        }
        if (!"im.message.receive_v1".equals(eventType)) {
            return Map.of();
        }
        JsonNode event = body.path("event");
        String eventId = body.path("header").path("event_id").asText("");
        if (inbox != null) inbox.enqueue(event, eventId, appId);
        else collect(event, eventId);
        return Map.of();
    }

    /** 已认证且加密持久化的事件异步处理；创建、幂等和完成标记在同一事务提交。 */
    @Transactional
    public void processInbox(cn.wayuwa.wao.domain.FeishuBotInboxEntity row) {
        inbox.requireLease(row);
        if (!settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED))
            throw new BadRequestException("飞书收集已暂停");
        if (!row.getAppId().equals(settings.get(SettingService.CHAT_SYNC_APP_ID, "")))
            throw new BadRequestException("飞书接收应用已改变");
        JsonNode event = inbox.event(row);
        if (!event.path("message").path("chat_id").asText("").equals(settings.get(SettingService.CHAT_SYNC_CHAT_ID, ""))
                || !StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")))
            throw new BadRequestException("飞书接收配置已改变");
        var route = botRegistry.captureRoute(event.path("message").path("mentions"));
        if (route == null || !java.util.Objects.equals(route.webhookId(), row.getWebhookId())
                || !route.kind().equals(row.getCaptureKind())) throw new BadRequestException("飞书收集路由已改变");
        if (event.path("_wao_polled_identity_check").asBoolean(false)) {
            var senderId = (com.fasterxml.jackson.databind.node.ObjectNode) event.path("sender").path("sender_id");
            senderId.put("union_id", polledSenderUnionId(event.path("message").path("chat_id").asText(""),
                    senderId.path("open_id").asText("")));
        }
        collect(event, row.getEventId());
        inbox.complete(row);
    }

    /**
     * 处理通过飞书开放 API 主动拉到的消息。该链路已由 tenant_access_token 鉴权，仍复用同一套
     * 身份归属、项目可见性、任务创建、回执与 message_id 持久去重逻辑。
     *
     * @param chatId 目标群 id
     * @param source 飞书消息
     */
    @Transactional
    public void collectPolled(String chatId, FeishuMessage source) {
        if (source == null || !"user".equals(source.senderType()) || source.deleted()) return;
        var event = json.createObjectNode();
        var senderId = event.putObject("sender").put("sender_type", "user").putObject("sender_id");
        senderId.put("open_id", source.senderId());
        var message = event.putObject("message");
        message.put("message_id", source.messageId());
        message.put("chat_id", chatId);
        message.put("message_type", source.msgType());
        message.put("content", source.content());
        var mentions = message.putArray("mentions");
        source.mentions().forEach(mention -> mentions.addObject()
                .put("key", mention.key()).put("name", mention.name())
                .putObject("id").put("open_id", mention.openId()));
        if (inbox != null) {
            // 先持久保留已认证消息，群成员接口的瞬时失败由现有收件箱重试，不能随着水位推进丢失。
            event.put("_wao_polled_identity_check", true);
            inbox.enqueue(event, "", settings.get(SettingService.CHAT_SYNC_APP_ID, ""));
        } else {
            senderId.put("union_id", polledSenderUnionId(chatId, source.senderId()));
            collect(event, "");
        }
    }

    /**
     * 用主动轮询返回的 {@code deleted=true} 事实删除原消息创建的任务。
     *
     * @param chatId 当前已配置的飞书群 id
     * @param messageId 已撤回的飞书消息 id
     */
    public void recallPolled(String chatId, String messageId) {
        recall(chatId, messageId, "");
    }

    /**
     * 用已经认证的飞书应用令牌读取原消息上的人工派工表情。
     *
     * <p>事件订阅仍用于低延迟触发；此入口让没有配置 Verification Token / Encrypt Key 的部署也能
     * 在现有消息轮询中可靠发现飞机表情。身份、原消息和幂等校验仍由派工服务统一执行。
     *
     * @param token tenant_access_token
     * @param messageId 已进入快速收集链路的原消息 id
     */
    public void triggerPolledReaction(String token, String messageId) {
        if (!StringUtils.hasText(messageId) || reactionAiRuns.hasTriggered(messageId)) return;
        long now = System.nanoTime();
        if (!claimReactionCheck(messageId, now)) return;
        try {
            List<FeishuMessageReaction> reactions = feishu.listMessageReactions(
                    token, messageId, reactionPolicy.triggerEmoji());
            for (FeishuMessageReaction reaction : reactions) {
                reactionAiRuns.triggerPolled(messageId, reaction, PUBLIC_BASE_URL);
                if (reactionAiRuns.hasTriggered(messageId)) return;
            }
        } catch (RuntimeException ex) {
            recentReactionChecks.remove(messageId);
            throw ex;
        }
    }

    private boolean claimReactionCheck(String messageId, long now) {
        long expiresAt = now + REACTION_CHECK_TTL_NANOS;
        Long previous = recentReactionChecks.putIfAbsent(messageId, expiresAt);
        if (previous != null && previous > now) return false;
        if (previous != null && !recentReactionChecks.replace(messageId, previous, expiresAt)) return false;
        if (recentReactionChecks.size() > MAX_RECENT_REACTION_CHECKS) {
            recentReactionChecks.entrySet().removeIf(entry -> entry.getValue() <= now);
            int excess = recentReactionChecks.size() - MAX_RECENT_REACTION_CHECKS;
            if (excess > 0) {
                recentReactionChecks.keySet().stream().limit(excess)
                        .forEach(recentReactionChecks::remove);
            }
        }
        return true;
    }

    /** 真正把一条群消息变成任务。 */
    private void collect(JsonNode event, String eventId) {
        JsonNode message = event.path("message");
        String chatId = message.path("chat_id").asText("");
        String configured = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (!settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)
                || !StringUtils.hasText(configured) || !configured.equals(chatId)
                || !StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_ID, ""))
                || !StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, ""))
                || !"user".equals(event.path("sender").path("sender_type").asText(""))) {
            return;
        }
        // 普通群聊必须保持零打扰；只有明确 @ 了机器人的消息才能进收集链路。
        if (!message.path("mentions").isArray() || message.path("mentions").isEmpty()) {
            return;
        }
        var route = botRegistry == null ? null : botRegistry.captureRoute(message.path("mentions"));
        String botKind = botRegistry == null ? botKind(message.path("mentions")) : route == null ? "NONE" : route.kind();
        if ("AMBIGUOUS".equals(botKind) || "NONE".equals(botKind)) return;
        String messageId = message.path("message_id").asText("");
        if (!StringUtils.hasText(messageId)) return;
        String eventKey = eventKey(eventId, messageId, event);
        String type = message.path("message_type").asText("");
        String fingerprint = sourceFingerprint(type, message.path("content").asText(""));
        ClaimMode claim = claim(eventKey, eventId, messageId, chatId,
                event.path("sender").path("sender_id").path("open_id").asText(""), fingerprint,
                route == null ? null : route.webhookId());
        if (claim == ClaimMode.NONE) {
            return;
        }
        if ("audio".equals(type)) {
            react(messageId, "ERROR");
            finish(eventKey, "IGNORED", botKind, null, fingerprint, null);
            return;
        }
        CaptureText capture;
        try {
            String content = readableText(type, message);
            capture = "NOTE".equals(botKind)
                    ? new CaptureText("NOTE", content, "") : captureText(content);
        } catch (RuntimeException ex) {
            finish(eventKey, "FAILED", botKind, null, null, "CAPTURE_FAILED");
            react(messageId, "ERROR");
            throw ex;
        }
        if (!StringUtils.hasText(capture.content())) {
            react(messageId, "ERROR");
            finish(eventKey, "REJECTED", capture.kind(), null, fingerprint, "EMPTY_CONTENT");
            return;
        }
        SenderBinding binding = senderBinding(event.path("sender"));
        String memberId = binding.memberId();
        if (!StringUtils.hasText(memberId)) {
            react(messageId, "ERROR");
            finish(eventKey, "REJECTED", capture.kind(), null, fingerprint, binding.errorCode());
            bindingGuide(chatId, messageId, binding.errorCode());
            return;
        }
        try {
            if (claim == ClaimMode.UPDATE) {
                FeishuQuickCollectEventEntity stored = events.selectById(eventKey);
                if (targetGuard != null && !targetGuard.mayUpdate(stored)) {
                    finish(eventKey, "REJECTED", stored.getCaptureKind(), stored.getTargetId(), fingerprint, "TARGET_CHANGED");
                    return;
                }
                if (!("NOTE".equals(stored.getCaptureKind()) == "NOTE".equals(capture.kind()))) {
                    finish(eventKey, "REJECTED", stored.getCaptureKind(), stored.getTargetId(), fingerprint, "ROUTE_CHANGED");
                    return;
                }
                if ("NOTE".equals(stored.getCaptureKind())) {
                    knowledge.createNote(stored.getTargetId(), noteRequest(capture.content()), memberId);
                    finish(eventKey, "UPDATED", "NOTE", stored.getTargetId(), fingerprint, null);
                    log.info("群聊个人收集：已更新小记 {}", stored.getTargetId());
                } else {
                    TaskDto task = tasks.updateFromChat(stored.getTargetId(), title(capture.content()),
                            capture.content(), memberId);
                    finish(eventKey, "UPDATED", capture.kind(), task.id(), fingerprint, null);
                    log.info("群聊个人收集：已从编辑后的飞书消息更新任务 {}", task.id());
                }
            } else if ("NOTE".equals(capture.kind())) {
                String noteId = noteId(eventKey);
                knowledge.createNote(noteId, noteRequest(capture.content()), memberId);
                finish(eventKey, "CREATED", "NOTE", noteId, fingerprint, null);
                log.info("群聊个人收集：已创建小记 {}", noteId);
            } else {
                ProjectDto project = targetProject(memberId);
                if (project == null) {
                    react(messageId, "ERROR");
                    finish(eventKey, "REJECTED", capture.kind(), null, fingerprint, "NO_PROJECT");
                    return;
                }
                TaskDto task = tasks.createFromChat(project.id(), title(capture.content()), capture.content(),
                        memberId, capture.sourceTag());
                finish(eventKey, "CREATED", capture.kind(), task.id(), fingerprint, null);
                log.info("群聊个人收集：已创建任务 {}（项目 {}）", task.id(), project.id());
            }
            react(messageId, reactionPolicy.receivedEmoji());
            if (targetGuard != null) targetGuard.remember(events.selectById(eventKey));
        } catch (RuntimeException ex) {
            finish(eventKey, "FAILED", capture.kind(), null, null, "CAPTURE_FAILED");
            react(messageId, "ERROR");
            throw ex;
        }
    }

    /** 两个群自定义机器人的真实 @ 元数据负责分流；同时 @ 两个时不猜用户意图。 */
    static String botKind(JsonNode mentions) {
        boolean task = false;
        boolean note = false;
        for (JsonNode mention : mentions) {
            if (TASK_BOT_NAME.equals(mention.path("name").asText())) task = true;
            if (NOTE_BOT_NAME.equals(mention.path("name").asText())) note = true;
        }
        return task && note ? "AMBIGUOUS" : note ? "NOTE" : task ? "TASK" : "NONE";
    }

    private static CreateNoteRequest noteRequest(String content) {
        String heading = title(content);
        return new CreateNoteRequest(IMAGE_ONLY_TITLE.equals(heading) ? "图片小记" : heading,
                List.of(new DocBlockDto(null, null, content, null, null)));
    }

    private static String noteId(String eventKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(eventKey.getBytes(StandardCharsets.UTF_8));
            return "fn" + java.util.HexFormat.of().formatHex(digest).substring(0, 30);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成飞书小记 ID", ex);
        }
    }

    /** 识别口语化前缀；没写命令时仍按普通任务收集。 */
    static CaptureText captureText(String raw) {
        String text = raw == null ? "" : raw.trim();
        String idea = stripPrefix(text, "记录想法", "记个想法", "添加想法", "想法", "点子");
        if (idea != null) {
            return new CaptureText("IDEA", idea, IDEA_SOURCE_TAG);
        }
        String task = stripPrefix(text, "记录任务", "记个任务", "添加任务", "新建任务", "任务", "待办", "事项");
        return new CaptureText("TASK", task == null ? text : task, SOURCE_TAG);
    }

    private static String stripPrefix(String text, String... prefixes) {
        String candidate = text.startsWith("帮我") ? text.substring(2).trim() : text;
        for (String prefix : prefixes) {
            if (candidate.startsWith(prefix)) {
                String rest = candidate.substring(prefix.length()).trim();
                return rest.replaceFirst("^[：:，,]\\s*", "").trim();
            }
        }
        return null;
    }

    private ClaimMode claim(String key, String eventId, String messageId, String chatId, String senderOpenId,
            String fingerprint, String webhookId) {
        FeishuQuickCollectEventEntity event = new FeishuQuickCollectEventEntity();
        event.setEventKey(key);
        event.setEventId(emptyToNull(eventId));
        event.setMessageId(emptyToNull(messageId));
        event.setChatId(emptyToNull(chatId));
        event.setSenderOpenId(emptyToNull(senderOpenId));
        event.setWebhookId(webhookId);
        event.setStatus("PROCESSING");
        event.setCreatedAt(LocalDateTime.now(SHANGHAI));
        event.setUpdatedAt(event.getCreatedAt());
        try {
            events.insert(event);
            return ClaimMode.CREATE;
        } catch (DuplicateKeyException ex) {
            FeishuQuickCollectEventEntity existing = events.selectById(key);
            if (existing == null || fingerprint.equals(existing.getSourceFingerprint())) {
                return ClaimMode.NONE;
            }
            LocalDateTime now = LocalDateTime.now(SHANGHAI);
            if (StringUtils.hasText(existing.getTargetId())) {
                return events.claimChangedMessage(key, fingerprint, now) == 1
                        ? ClaimMode.UPDATE : ClaimMode.NONE;
            }
            return "FAILED".equals(existing.getStatus()) && events.retryFailedCreate(key, now) == 1
                    ? ClaimMode.CREATE : ClaimMode.NONE;
        }
    }

    private void finish(String key, String status, String kind, String targetId, String fingerprint, String error) {
        LocalDateTime updatedAt = LocalDateTime.now(SHANGHAI);
        int updated = events.finishUnlessRecalled(key, status, kind, targetId, fingerprint, error, updatedAt);
        if (updated != 0 || !StringUtils.hasText(targetId)) {
            return;
        }
        FeishuQuickCollectEventEntity latest = events.selectById(key);
        if (latest != null && ("RECALLING".equals(latest.getStatus())
                || "RECALLED".equals(latest.getStatus()))) {
            events.attachRecalledTarget(key, targetId, fingerprint, updatedAt);
            if ("NOTE".equals(kind)) {
                knowledge.deleteNoteFromChat(targetId);
            } else {
                tasks.deleteFromChat(targetId);
            }
            events.completeRecall(key, LocalDateTime.now(SHANGHAI));
        }
    }

    /** 只接受当前已配置群里的撤回，避免免登录事件入口扩大删除权限。 */
    private void recall(JsonNode event, String eventId) {
        recall(event.path("chat_id").asText(""), event.path("message_id").asText(""), eventId);
    }

    private void recall(String chatId, String messageId, String eventId) {
        String configured = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (!StringUtils.hasText(configured) || !configured.equals(chatId)) {
            return;
        }
        recalls.recall(eventId, messageId, chatId);
    }

    private String eventKey(String eventId, String messageId, JsonNode event) {
        // 回调和主动轮询看到的 event_id 不同（轮询没有 event_id），
        // 但 message_id 是同一条群消息的稳定身份，必须优先用它才能跨入口去重。
        if (StringUtils.hasText(messageId)) {
            return "message:" + messageId;
        }
        if (StringUtils.hasText(eventId)) {
            return "event:" + eventId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(event.toString().getBytes(StandardCharsets.UTF_8));
            return "body:" + java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成事件去重键", ex);
        }
    }

    /** 把消息内容读成一行可用的文字，并去掉 @ 机器人留下的占位符。 */
    private String readableText(String type, JsonNode message) {
        JsonNode content = read(message.path("content").asText("{}"));
        String text = switch (type) {
            case "text" -> content.path("text").asText("");
            case "post" -> postText(content, message.path("message_id").asText(""), message.path("mentions"));
            default -> "";
        };
        List<JsonNode> mentions = new ArrayList<>();
        message.path("mentions").forEach(mentions::add);
        mentions.sort(java.util.Comparator.comparingInt((JsonNode mention) -> mention.path("key").asText("").length()).reversed());
        for (JsonNode mention : mentions) {
            String key = mention.path("key").asText("");
            if (StringUtils.hasText(key)) {
                String name = mention.path("name").asText("");
                text = text.replace(key, entryMention(mention) ? "" : StringUtils.hasText(name) ? "@" + name : key);
            }
        }
        return text.trim();
    }

    /** 富文本消息：先保留全部文字段落，再把图片按原有顺序追加到正文末尾。 */
    private String postText(JsonNode content, String messageId, JsonNode mentions) {
        StringBuilder text = new StringBuilder();
        List<String> imageMarkdown = new ArrayList<>();
        String title = content.path("title").asText("");
        if (StringUtils.hasText(title)) {
            text.append(title).append('\n');
        }
        for (JsonNode line : content.path("content")) {
            for (JsonNode span : line) {
                if ("text".equals(span.path("tag").asText(""))) {
                    text.append(span.path("text").asText(""));
                } else if ("a".equals(span.path("tag").asText(""))) {
                    text.append(span.path("text").asText("")).append(' ')
                            .append(span.path("href").asText(""));
                } else if ("at".equals(span.path("tag").asText(""))) {
                    text.append(postMentionText(span, mentions));
                } else if ("img".equals(span.path("tag").asText(""))) {
                    String key = span.path("image_key").asText("");
                    if (StringUtils.hasText(key)) {
                        imageMarkdown.add("![任务图片](" + storeImage(messageId, key) + ")");
                    }
                }
            }
            text.append('\n');
        }
        String body = text.toString().trim();
        if (imageMarkdown.isEmpty()) {
            return body;
        }
        String images = String.join("\n\n", imageMarkdown);
        return body.isEmpty() ? images : body + "\n\n" + images;
    }

    private boolean entryMention(JsonNode mention) {
        var single = json.createArrayNode().add(mention);
        return botRegistry == null ? !"NONE".equals(botKind(single)) : botRegistry.captureRoute(single) != null;
    }

    private String postMentionText(JsonNode span, JsonNode mentions) {
        String id = span.path("user_id").asText("");
        for (JsonNode mention : mentions) {
            if (StringUtils.hasText(id) && (id.equals(mention.path("id").path("open_id").asText(""))
                    || id.equals(mention.path("id").path("user_id").asText(""))
                    || id.equals(mention.path("key").asText("")))) {
                if (entryMention(mention)) return "";
                String name = mention.path("name").asText("");
                return "@" + (StringUtils.hasText(name) ? name : id);
            }
        }
        String name = span.path("user_name").asText("");
        return StringUtils.hasText(name) ? "@" + name : StringUtils.hasText(id) ? "@" + id : "";
    }

    /** 用当前应用的真实消息资源权限下载图片并转存 WAO 图床。 */
    private String storeImage(String messageId, String imageKey) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw new IllegalStateException("飞书应用未配置，无法转存消息图片");
        }
        String token = feishu.tenantAccessToken(appId, appSecret);
        byte[] bytes = feishu.downloadImage(token, messageId, imageKey);
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("飞书消息图片下载失败：" + imageKey);
        }
        return images.store(bytes, "image/png", imageKey + ".png", "飞书任务 · " + messageId, true).url();
    }

    private static String sourceFingerprint(String type, String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((type + "\n" + content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成飞书消息内容指纹", ex);
        }
    }

    /**
     * 标题取第一段非图片可见文字；纯图片消息使用可读标题，绝不暴露 Markdown 路径或 HTML 标签。
     *
     * <p>飞书 {@code post} 的 {@code text} 节点通常是纯文本，但粘贴或机器人转发的内容偶尔会是
     * {@code <p>正文</p>}。描述编辑器能渲染这段 HTML，任务标题却是纯文本字段，因此在标题边界统一
     * 把块标签还原成换行、移除其余标签并解码实体；描述仍保留原文，不破坏已有段落格式。
     */
    static String title(String text) {
        String visible = HTML_BLOCK_BOUNDARY.matcher(text == null ? "" : text).replaceAll("\n");
        visible = HtmlUtils.htmlUnescape(HTML_TAG.matcher(visible).replaceAll(""));
        String first = visible.lines().map(String::trim)
                .filter(StringUtils::hasText)
                .filter(line -> !line.matches("^!\\[[^]]*]\\([^\\r\\n]+\\)$"))
                .findFirst().orElse(IMAGE_ONLY_TITLE);
        return first.length() <= TITLE_LIMIT ? first : first.substring(0, TITLE_LIMIT) + "…";
    }

    /** 落点项目：先看配置，其次按名字找「Wa All in One」，最后退回第一个项目。 */
    private ProjectDto targetProject(String memberId) {
        List<ProjectDto> visible = projects.list(memberId);
        String configured = settings.get(SettingService.QUICK_COLLECT_PROJECT_ID, "");
        if (StringUtils.hasText(configured)) {
            ProjectDto project = visible.stream().filter(item -> configured.equals(item.id())).findFirst().orElse(null);
            if (project != null) {
                return project;
            }
        }
        return visible.stream().filter(p -> DEFAULT_PROJECT_NAME.equalsIgnoreCase(p.name())).findFirst()
                .orElseGet(() -> visible.isEmpty() ? null : visible.get(0));
    }

    /** 发言人对应的 WAO 成员；未绑定时必须拒绝，不能把私人待办记到别人名下。 */
    private SenderBinding senderBinding(JsonNode sender) {
        String openId = sender.path("sender_id").path("open_id").asText("");
        if (!StringUtils.hasText(openId)) return new SenderBinding(null, "SENDER_NOT_BOUND");
        AccountIdentityEntity direct = identities.findByProviderSubject("feishu", openId);
        String unionId = sender.path("sender_id").path("union_id").asText("");
        AccountIdentityEntity union = StringUtils.hasText(unionId)
                ? identities.findByProviderUnionSubject("feishu", unionId) : null;
        if (direct != null && union != null && !direct.getAccountId().equals(union.getAccountId()))
            return new SenderBinding(null, "SENDER_IDENTITY_CONFLICT");
        String member = memberId(direct != null ? direct : union);
        return new SenderBinding(member, StringUtils.hasText(member) ? null : "SENDER_NOT_BOUND");
    }

    private void bindingGuide(String chatId, String messageId, String errorCode) {
        String text = "SENDER_IDENTITY_CONFLICT".equals(errorCode)
                ? "飞书身份归属冲突，未创建记录。请登录 WAO 核对账号绑定，或联系管理员。"
                : "未创建记录：请先登录 WAO，在账号设置中绑定当前飞书身份，再发送一条新的 @ 消息。";
        text += "\n" + PUBLIC_BASE_URL + "/settings?tab=account";
        try {
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (StringUtils.hasText(appId) && StringUtils.hasText(secret))
                feishu.sendTextMessage(feishu.tenantAccessToken(appId, secret), chatId, text,
                        "bind-" + sourceFingerprint("guide", messageId).substring(0, 40), messageId);
        } catch (RuntimeException ex) { log.warn("飞书身份绑定指引未能投递，拒绝结果已保留"); }
    }

    /** 主动轮询只给出机器人应用 Open ID，通过群成员列表安全换成 Union ID。 */
    private String polledSenderUnionId(String chatId, String openId) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)
                || !StringUtils.hasText(chatId) || !StringUtils.hasText(openId)) {
            throw new BadRequestException("飞书群成员身份暂无法核实");
        }
        try {
            String unionId = feishu.chatMemberUnionId(feishu.tenantAccessToken(appId, appSecret), chatId, openId);
            if (!StringUtils.hasText(unionId)) throw new BadRequestException("飞书群成员身份暂无法唯一核实");
            return unionId;
        } catch (RuntimeException ex) {
            log.warn("飞书群成员跨应用身份换算失败：{}", ex.getClass().getSimpleName());
            throw new BadRequestException("飞书群成员身份暂无法核实，请稍后重试");
        }
    }

    private String memberId(AccountIdentityEntity identity) {
        if (identity == null) return null;
        AccountEntity account = accounts.selectById(identity.getAccountId());
        return account != null && StringUtils.hasText(account.getMemberId()) ? account.getMemberId() : null;
    }

    /** 只给原消息加表情回执；失败时记日志，不向群内补发卡片或 Webhook。 */
    private void react(String messageId, String emojiType) {
        if (!StringUtils.hasText(messageId)) {
            return;
        }
        try {
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (StringUtils.hasText(appId) && StringUtils.hasText(appSecret)) {
                feishu.addMessageReaction(feishu.tenantAccessToken(appId, appSecret), messageId, emojiType);
            }
        } catch (RuntimeException ex) {
            log.warn("群聊快速收集表情回执失败：{}", ex.getClass().getSimpleName());
        }
    }

    /** 配置过 Verification Token 时才校验；没配就跳过（飞书那边也可以不开）。 */
    private void requireToken(String presented, boolean encryptedEnvelope) {
        String expected = settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN);
        if (!StringUtils.hasText(expected) && !(encryptedEnvelope
                && StringUtils.hasText(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY))))
            throw new BadRequestException("飞书回调未配置身份校验，请配置 Verification Token 或加密事件");
        if (StringUtils.hasText(expected) && !expected.equals(presented)) {
            throw new BadRequestException("飞书事件校验失败");
        }
    }

    /**
     * 解开事件体：配了 Encrypt Key 就按飞书的 AES-256-CBC 规则解密，否则原样解析。
     *
     * <p>飞书的密文是 Base64(IV + 密文)，密钥是 Encrypt Key 的 SHA-256。
     */
    private JsonNode decrypt(String rawBody) {
        JsonNode body = read(rawBody);
        String encrypted = body.path("encrypt").asText("");
        if (!StringUtils.hasText(encrypted)) {
            return body;
        }
        String key = settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY);
        if (!StringUtils.hasText(key)) {
            throw new BadRequestException("飞书事件是加密的，请先在设置里填写 Encrypt Key");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted);
            byte[] secret = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new IvParameterSpec(java.util.Arrays.copyOfRange(all, 0, 16)));
            byte[] plain = cipher.doFinal(java.util.Arrays.copyOfRange(all, 16, all.length));
            return read(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new BadRequestException("飞书事件解密失败，请检查 Encrypt Key 是否与开放平台一致");
        }
    }

    /** 已勾选的 @ 名单。 */
    private List<String> mentionOpenIds() {
        List<String> out = new ArrayList<>(new LinkedHashSet<>(
                List.of(settings.get(SettingService.QUICK_COLLECT_MENTIONS, "").split(","))));
        out.removeIf(value -> !StringUtils.hasText(value));
        return out.stream().map(String::trim).toList();
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(StringUtils.hasText(text) ? text : "{}");
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String abbreviated(String value) {
        if (!StringUtils.hasText(value)) {
            return "UNKNOWN";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    record CaptureText(String kind, String content, String sourceTag) { }
    private record SenderBinding(String memberId, String errorCode) { }

    private enum ClaimMode { CREATE, UPDATE, NONE }
}
