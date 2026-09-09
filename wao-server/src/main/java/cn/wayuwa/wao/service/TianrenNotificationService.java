package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.domain.TianrenNotificationEntity;
import cn.wayuwa.wao.dto.TianrenNotificationDto;
import cn.wayuwa.wao.dto.TianrenReadingDto;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import cn.wayuwa.wao.mapper.TianrenNotificationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 仅在成员主动开启后，投递真实卦象及已保存解读；不依赖浏览器长连接。 */
@Service
public class TianrenNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TianrenNotificationService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> RETRYABLE = Set.of("FAILED", "SKIPPED", "UNKNOWN");
    private final TianrenNotificationMapper deliveries;
    private final TianrenDailyReadingMapper readingMapper;
    private final TianrenReadingService readings;
    private final AiAssistantConversationService conversations;
    private final MemberPreferenceMapper preferences;
    private final NotificationSettingsService policies;
    private final SettingService settings;
    private final FeishuWebhookConfigService webhooks;
    private final FeishuWebhookNotifier webhookSender;
    private final FeishuOpenApiClient feishu;
    private final ObjectMapper json;
    private final AiAssistantRunMapper assistantRuns;
    private String cursor = "";

    public TianrenNotificationService(TianrenNotificationMapper deliveries, TianrenDailyReadingMapper readingMapper,
            TianrenReadingService readings, AiAssistantConversationService conversations,
            MemberPreferenceMapper preferences, NotificationSettingsService policies, SettingService settings,
            FeishuWebhookConfigService webhooks, FeishuWebhookNotifier webhookSender,
            FeishuOpenApiClient feishu, ObjectMapper json, AiAssistantRunMapper assistantRuns) {
        this.deliveries = deliveries;
        this.readingMapper = readingMapper;
        this.readings = readings;
        this.conversations = conversations;
        this.preferences = preferences;
        this.policies = policies;
        this.settings = settings;
        this.webhooks = webhooks;
        this.webhookSender = webhookSender;
        this.feishu = feishu;
        this.json = json;
        this.assistantRuns = assistantRuns;
    }

    /** 每次有界扫描当天存档，准备快照并抢占投递；超时不自动重发。 */
    public synchronized void tick() {
        LocalDateTime now = now();
        deliveries.expire(now, now.minusMinutes(5));
        List<TianrenDailyReadingEntity> batch = deliveries.candidates(LocalDate.now(ZONE), cursor);
        for (TianrenDailyReadingEntity row : batch) {
            try { prepare(row); }
            catch (RuntimeException ex) { log.warn("卦象通知准备失败：{}", ex.getClass().getSimpleName()); }
        }
        cursor = batch.size() < 50 ? "" : batch.get(batch.size() - 1).getId();
        for (TianrenNotificationEntity delivery : deliveries.pending()) send(delivery);
    }

    /** 只读返回归属当前成员的投递状态，不因为打开页面而触发分享或发送。 */
    public TianrenNotificationDto status(String memberId, String readingId) {
        TianrenDailyReadingEntity reading = owned(memberId, readingId);
        TianrenNotificationEntity row = deliveries.find(readingId);
        if (row != null) return dto(row);
        JsonNode config = config(memberId);
        if (!enabled(config)) return new TianrenNotificationDto("DISABLED", "", "卦象群通知未启用", false);
        if (!LocalDate.now(ZONE).equals(reading.getReadingDate())) {
            return new TianrenNotificationDto("SKIPPED", "", "历史卦象不自动补发", false);
        }
        if (targetId(config).isBlank()) return new TianrenNotificationDto("CONFIGURATION_REQUIRED", "",
                "请在今日节奏的通知配置中选择目标群或 Webhook", false);
        if (StringUtils.hasText(reading.getConversationId())) {
            AiAssistantRunEntity latest = assistantRuns.selectOne(new LambdaQueryWrapper<AiAssistantRunEntity>()
                    .eq(AiAssistantRunEntity::getConversationId, reading.getConversationId())
                    .eq(AiAssistantRunEntity::getMemberId, memberId)
                    .orderByDesc(AiAssistantRunEntity::getCreatedAt).last("LIMIT 1"));
            if (latest != null && "failed".equals(latest.getStatus())) {
                return new TianrenNotificationDto("INTERPRETATION_FAILED", targetName(config),
                        "AI 解读未完成，卡片尚未发送；自动流程最多尝试三次，也可在卦象页点击解读按钮重试。", false);
            }
            if (latest != null && !"succeeded".equals(latest.getStatus())) {
                return new TianrenNotificationDto("INTERPRETING", targetName(config), "正在生成今日解读，完成后自动发送卡片", false);
            }
        }
        return new TianrenNotificationDto("WAITING", targetName(config), "等待已保存的解读与服务端投递", false);
    }

    /** 仅重试已存在的失败快照；不再次起卦或调用 AI，未知结果须先确认。 */
    public TianrenNotificationDto retry(String memberId, String readingId, boolean confirmNotReceived) {
        owned(memberId, readingId);
        TianrenNotificationEntity row = deliveries.find(readingId);
        if (row == null) throw new BadRequestException("尚无可重试的投递，请先完成解读并开启群通知");
        if (!RETRYABLE.contains(row.state())) return dto(row);
        if ("UNKNOWN".equals(row.state()) && !confirmNotReceived) {
            throw new BadRequestException("请先确认群里没有收到卡片；重试可能造成重复消息");
        }
        String blocked = blocked(memberId, row);
        if (blocked != null) throw new BadRequestException(blocked);
        transition(row, row.state(), "PENDING", "已安排重试原群的卡片快照", null);
        return dto(deliveries.find(readingId));
    }

    private void prepare(TianrenDailyReadingEntity row) {
        JsonNode config = config(row.getMemberId());
        if (!enabled(config) || targetId(config).isBlank()) return;
        String interpretation = conversations.latestAssistantReply(row.getConversationId(), row.getMemberId());
        if (!StringUtils.hasText(interpretation)) return;
        TianrenReadingDto reading = readings.getOwned(row.getMemberId(), row.getId());
        boolean allowed = policies.allowsReminders();
        // 只有确实要公开投递时才生成公开凭据；策略跳过不制造新的公开链接。
        String url = allowed ? FeishuAppLinks.SITE + "/s/tianren/"
                + readings.createShareToken(row.getMemberId(), row.getId()) : "";
        String payload;
        try { payload = json.writeValueAsString(card(reading, interpretation, url)); }
        catch (Exception ex) { throw new IllegalStateException("卡片快照无法保存", ex); }
        deliveries.insert(new TianrenNotificationEntity(row.getId(), allowed ? "PENDING" : "SKIPPED",
                channel(config), targetId(config), targetName(config), payload, null,
                allowed ? "等待发送" : policies.blockedReason(), now()));
    }

    private void send(TianrenNotificationEntity row) {
        String attemptToken = java.util.UUID.randomUUID().toString();
        if (deliveries.claim(row.readingId(), attemptToken, now()) != 1) return;
        boolean attempted = false;
        try {
            TianrenDailyReadingEntity reading = readingMapper.selectById(row.readingId());
            if (reading == null) return;
            String blocked = blocked(reading.getMemberId(), row);
            if (blocked != null) { deliveries.finish(row.readingId(), attemptToken, "SKIPPED", blocked, null, now()); return; }
            JsonNode payload = json.readTree(row.payload());
            // 沉默时段准备的快照没有公开链接；获准人工重试时补上稳定公开链接。
            if (!payload.toString().contains("/s/tianren/")) {
                String link = FeishuAppLinks.SITE + "/s/tianren/"
                        + readings.createShareToken(reading.getMemberId(), reading.getId());
                ((com.fasterxml.jackson.databind.node.ArrayNode) payload.path("body").path("elements"))
                        .add(json.valueToTree(markdown("[查看完整解读](" + link + ")")));
            }
            String messageId = null;
            if ("WEBHOOK".equals(row.channel())) {
                var target = webhooks.enabledTarget(row.targetId());
                attempted = true;
                webhookSender.sendRawCard(target.url(), payload);
            } else {
                String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
                String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
                if (!StringUtils.hasText(appId) || !StringUtils.hasText(secret)) throw new BadRequestException("飞书应用未配置");
                String token = feishu.tenantAccessToken(appId, secret);
                attempted = true;
                messageId = feishu.sendRawCard(token, row.targetId(), payload, "tianren-" + row.readingId());
            }
            deliveries.finish(row.readingId(), attemptToken, "SENT", "飞书已确认接收", messageId, now());
        } catch (Exception ex) {
            deliveries.finish(row.readingId(), attemptToken, attempted ? "UNKNOWN" : "FAILED", attempted
                    ? "未能确认飞书接收结果，请核对群消息、图片权限及机器人配置；确认未收到后再重试"
                    : "发送配置不可用，请检查原目标群、Webhook 或飞书应用配置后重试", null, now());
            log.warn("卦象通知未确认成功：{}", ex.getClass().getSimpleName());
        }
    }

    private String blocked(String memberId, TianrenNotificationEntity row) {
        JsonNode current = config(memberId);
        if (!enabled(current)) return "卦象群通知已关闭";
        if (!policies.allowsReminders()) return policies.blockedReason();
        if (!row.channel().equals(channel(current)) || !row.targetId().equals(targetId(current))) {
            return "通知目标已改变；为避免误发，请恢复原目标后再重试这张卡片";
        }
        return null;
    }

    private TianrenDailyReadingEntity owned(String memberId, String readingId) {
        TianrenDailyReadingEntity row = readingMapper.selectById(readingId);
        if (row == null || !row.getMemberId().equals(memberId)) throw new NotFoundException("卦象不存在");
        return row;
    }

    private JsonNode config(String memberId) {
        try {
            String raw = preferences.find(memberId, WorkdayRhythmNotificationService.CONFIG_KEY);
            return raw == null ? json.createObjectNode()
                    : WorkdayNotificationTargets.resolve(json.readTree(raw).path("energyNotification"), "tianrenTarget");
        } catch (Exception ex) { return json.createObjectNode(); }
    }

    private static boolean enabled(JsonNode config) {
        return config.path("enabled").isBoolean() && config.path("enabled").asBoolean()
                && config.path("notifyTianren").isBoolean() && config.path("notifyTianren").asBoolean();
    }
    private static String channel(JsonNode config) { return config.path("channel").asText("CHAT"); }
    private static String targetId(JsonNode config) {
        if (!Set.of("WEBHOOK", "CHAT").contains(channel(config))) return "";
        return config.path("WEBHOOK".equals(channel(config)) ? "webhookId" : "chatId").asText("").trim();
    }
    private static String targetName(JsonNode config) {
        return config.path("WEBHOOK".equals(channel(config)) ? "webhookName" : "chatName").asText("").trim();
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZONE); }
    private void transition(TianrenNotificationEntity row, String expected, String next, String message, String messageId) {
        deliveries.transition(row.readingId(), expected, next, message, messageId, now());
    }
    private static TianrenNotificationDto dto(TianrenNotificationEntity row) {
        return new TianrenNotificationDto(row.state(), row.targetName(), row.message(), RETRYABLE.contains(row.state()));
    }

    static Map<String, Object> card(TianrenReadingDto reading, String interpretation, String url) {
        String facts = "### <font color='purple'>【" + reading.primary().name() + "卦】</font>\n"
                + reading.date() + " · 上" + reading.primary().upper().name() + "下" + reading.primary().lower().name()
                + (reading.relating() == null ? " · 无变爻" : " · 之卦：" + reading.relating().name()
                        + " · 变爻：" + reading.changingLines());
        String safe = interpretation.replaceAll("(?s)<[^>]*>", "")
                .replaceAll("!?\\[([^]]*)]\\([^)]*\\)", "$1")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        if (safe.length() > 5500) safe = safe.substring(0, 5500) + "\n\n（内容较长，完整解读请打开结果页）";
        String content = facts + "\n\n**<font color='violet'>卦象解读 · AI 概括与延伸</font>**\n" + safe
                + "\n\n文化反思，不预测吉凶，也不替代现实判断或医疗建议。"
                + (url.isBlank() ? "" : "\n\n[查看完整解读](" + url + ")");
        return Map.of("schema", "2.0", "config", Map.of("update_multi", true),
                "header", Map.of("title", Map.of("tag", "plain_text", "content", "今日卦象 · 运势指引"),
                        "template", "purple", "icon", Map.of("tag", "standard_icon", "token", "divination"),
                        "padding", "12px 12px 12px 12px"),
                "body", Map.of("direction", "vertical", "elements", List.of(
                        Map.of("tag", "img", "img_key", "img_v3_02157_7be3ce53-692e-4549-b0f9-c3d9872c0cdg",
                                "scale_type", "fit_horizontal", "corner_radius", "12px", "margin", "0px 0px 0px 0px"),
                        markdown(content))));
    }
    private static Map<String, Object> markdown(String content) {
        return Map.of("tag", "markdown", "content", content, "text_size", "normal", "margin", "0px 0px 0px 0px");
    }
}
