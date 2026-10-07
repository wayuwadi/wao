package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.NotFoundException;
import cn.wayuwa.wao.domain.FeishuWebhookConfigEntity;
import cn.wayuwa.wao.domain.FeishuQuickCollectEventEntity;
import cn.wayuwa.wao.domain.FeishuBotSendEntity;
import cn.wayuwa.wao.dto.FeishuBotActivityDto;
import cn.wayuwa.wao.dto.FeishuBotActivityDto.*;
import cn.wayuwa.wao.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 机器人能力中心的引用影响与真实结果。 */
@Service
public class FeishuBotActivityService {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuBotActivityService.class);
    private final FeishuWebhookConfigMapper bots;
    private final FeishuQuickCollectEventMapper captures;
    private final FeishuBotSendMapper sends;
    private final FeishuBotUsageMapper references;
    private final SettingService settings;
    private final ObjectMapper json;
    private final FeishuBotInboxMapper inbox;

    public FeishuBotActivityService(FeishuWebhookConfigMapper bots, FeishuQuickCollectEventMapper captures,
            FeishuBotSendMapper sends, FeishuBotUsageMapper references, SettingService settings, ObjectMapper json,
            FeishuBotInboxMapper inbox) {
        this.bots = bots; this.captures = captures; this.sends = sends; this.references = references;
        this.settings = settings; this.json = json; this.inbox = inbox;
    }

    /** 读取一个机器人的引用与最近结果；历史成员目标明确标为保留配置。 */
    public FeishuBotActivityDto activity(String id) {
        FeishuWebhookConfigEntity bot = bots.selectById(id);
        if (bot == null) throw new NotFoundException("飞书机器人配置不存在");
        List<Usage> usages = usages(bot);
        List<Send> recentSends = sends.selectList(new QueryWrapper<FeishuBotSendEntity>()
                .eq("webhook_id", id).orderByDesc("occurred_at").last("LIMIT 20")).stream()
                .map(row -> new Send(row.getKind(), row.getStatus(), row.getOccurredAt(), row.getErrorCode())).toList();
        List<Capture> recentCaptures = new ArrayList<>(captures.selectList(new QueryWrapper<FeishuQuickCollectEventEntity>()
                .eq("webhook_id", id).orderByDesc("updated_at").last("LIMIT 20")).stream()
                .map(row -> new Capture(row.getMessageId(), row.getCaptureKind(), row.getStatus(),
                        row.getTargetId(), row.getUpdatedAt(), safeCaptureError(row.getErrorMessage()))).toList());
        inbox.selectList(new QueryWrapper<cn.wayuwa.wao.domain.FeishuBotInboxEntity>()
                .eq("webhook_id", id).in("status", List.of("PENDING", "PROCESSING", "FAILED"))
                .orderByDesc("updated_at").last("LIMIT 20")).forEach(row -> {
                    boolean newer = recentCaptures.stream().noneMatch(capture -> row.getMessageId().equals(capture.messageId())
                            && !capture.occurredAt().isBefore(row.getUpdatedAt()));
                    if (newer) {
                        recentCaptures.removeIf(capture -> row.getMessageId().equals(capture.messageId()));
                        recentCaptures.add(new Capture(row.getMessageId(), row.getCaptureKind(), row.getStatus(),
                                null, row.getUpdatedAt(), row.getErrorCode()));
                    }
                });
        recentCaptures.sort(java.util.Comparator.comparing(Capture::occurredAt).reversed());
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String chatId = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        Receiver receiver = new Receiver(settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED),
                has(appId) && has(settings.get(SettingService.CHAT_SYNC_APP_SECRET)), has(chatId),
                has(settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN))
                        || has(settings.get(SettingService.QUICK_COLLECT_ENCRYPT_KEY)),
                appId, chatId, settings.get(SettingService.CHAT_SYNC_CHAT_NAME, ""),
                "https://wao.wayuwa.cn/api/feishu/events", 0, "EVENT");
        FeishuWebhookConfigEntity replacement = "SYSTEM".equals(bot.getDefaultMarker())
                ? bots.selectList(new QueryWrapper<FeishuWebhookConfigEntity>().ne("id", id)
                        .orderByDesc("updated_at").orderByAsc("id").last("LIMIT 1")).stream().findFirst().orElse(null)
                : null;
        return new FeishuBotActivityDto(usages, recentSends, recentCaptures.stream().limit(20).toList(), receiver, usages.isEmpty(),
                replacement == null ? null : replacement.getId(), replacement == null ? null : replacement.getName());
    }

    /** 计算所有仍保存此稳定 id 的引用，供停用、删除前确认。 */
    public List<Usage> usages(FeishuWebhookConfigEntity bot) {
        List<Usage> out = new ArrayList<>();
        if ("SYSTEM".equals(bot.getDefaultMarker())) out.add(new Usage("SYSTEM_DEFAULT", "重置卡增加通知的系统默认目标", 1));
        if (has(bot.getCaptureKind()) && !"NONE".equals(bot.getCaptureKind()))
            out.add(new Usage("CAPTURE", "NOTE".equals(bot.getCaptureKind()) ? "群消息创建小记" : "群消息创建个人待办", 1));
        long preferenceCount = references.workdayPreferences().stream().filter(raw -> mentions(raw, bot.getId())).count();
        add(out, "SAVED_PREFERENCE", "今日节奏成员保留的 Webhook 选择（常规提醒现发往站内）", preferenceCount);
        add(out, "CHECKLIST", "今日节奏清单保留的 Webhook 选择", references.checklistReferences(bot.getId()));
        add(out, "AUTO_RUN", "自动运行策略保留的 Webhook 选择（现发往站内）", references.autoRunReferences(bot.getId()));
        if (bot.getId().equals(settings.get(SettingService.HTTP_INSPECTION_NOTIFICATION_WEBHOOK_ID)))
            out.add(new Usage("HTTP_INSPECTION", "HTTP 巡检保留的 Webhook 选择（现发往站内）", 1));
        return List.copyOf(out);
    }

    /** 持久记录已发生的发送结果；审计失败不能把已经发送的消息伪装成失败。 */
    public void recordSend(String id, String kind, boolean success) {
        if (!has(id)) return;
        FeishuBotSendEntity row = new FeishuBotSendEntity();
        row.setId(UUID.randomUUID().toString().replace("-", "")); row.setWebhookId(id);
        row.setKind(kind); row.setStatus(success ? "SUCCESS" : "FAILED");
        row.setErrorCode(success ? null : "DELIVERY_FAILED");
        row.setOccurredAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        try { sends.insert(row); }
        catch (RuntimeException ex) { LOG.warn("飞书机器人发送结果记录失败"); }
    }

    private boolean mentions(String raw, String id) {
        try { return mentions(json.readTree(raw), id); } catch (Exception ex) { return false; }
    }
    private boolean mentions(JsonNode node, String id) {
        if (node == null) return false;
        if (node.isObject() && id.equals(node.path("webhookId").asText())) return true;
        for (JsonNode child : node) if (mentions(child, id)) return true;
        return false;
    }
    private static void add(List<Usage> out, String kind, String label, long count) {
        if (count > 0) out.add(new Usage(kind, label, count));
    }
    private static boolean has(String value) { return value != null && !value.isBlank(); }
    private static String safeCaptureError(String value) {
        if (value == null || value.isBlank()) return null;
        return value.matches("[A-Z_]{1,64}") ? value : "CAPTURE_FAILED";
    }
}
