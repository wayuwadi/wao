package cn.wayuwa.wao.service;

import java.time.LocalDate;
import java.util.List;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.MemberEntity;
import cn.wayuwa.wao.dto.EnergyNotificationResultDto;
import cn.wayuwa.wao.dto.SendEnergyNotificationRequest;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.MemberMapper;
import cn.wayuwa.wao.mapper.MemberPreferenceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 今日节奏的成员级行动通知。目标来自成员偏好，Webhook 与应用凭据只在服务端读取。 */
@Service
public class WorkdayRhythmNotificationService {

    static final String CONFIG_KEY = "workday-rhythm-config-v1";
    private static final String DAY_KEY_PREFIX = "workday-rhythm-day-";

    private final MemberPreferenceMapper preferences;
    private final MemberMapper members;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuAppLinks links;
    private final FeishuWebhookConfigService webhooks;
    private final FeishuWebhookNotifier webhookNotifier;
    private final NotificationSettingsService notificationSettings;
    private final ObjectMapper json;

    public WorkdayRhythmNotificationService(MemberPreferenceMapper preferences, MemberMapper members,
            SettingService settings, FeishuOpenApiClient feishu, FeishuAppLinks links,
            FeishuWebhookConfigService webhooks, FeishuWebhookNotifier webhookNotifier,
            NotificationSettingsService notificationSettings, ObjectMapper json) {
        this.preferences = preferences;
        this.members = members;
        this.settings = settings;
        this.feishu = feishu;
        this.links = links;
        this.webhooks = webhooks;
        this.webhookNotifier = webhookNotifier;
        this.notificationSettings = notificationSettings;
        this.json = json;
    }

    /**
     * 按当前成员的配置，把已经成功落库的喝水或冥想记录发送到指定飞书群。
     *
     * @param memberId 当前登录成员 id
     * @param request 动作与日期；次数和完成状态会从该成员的当天记录重新读取
     * @return 是否实际发送，以及目标群名称
     */
    public EnergyNotificationResultDto notifyEnergy(String memberId, SendEnergyNotificationRequest request) {
        JsonNode config = read(preferences.find(memberId, CONFIG_KEY));
        JsonNode notification = config.path("energyNotification");
        EnergyAction action = action(config, request.action());
        String targetName = targetName(notification);
        if (!notification.path("enabled").asBoolean(false)
                || !notification.path("notifyEnergy").asBoolean(true)
                || !action.shouldNotify()) {
            return new EnergyNotificationResultDto(false, targetName);
        }

        JsonNode day = read(preferences.find(memberId, DAY_KEY_PREFIX + request.date()));
        String memberName = memberName(memberId);
        FeishuWebhookNotifier.Card card = card(action, request.action(), request.date(), day, memberName);
        return send(notification, card);
    }

    /**
     * 把当前成员已落库的当日重点发到本人选择的飞书目标。
     *
     * @param memberId 当前登录成员 id
     * @param date 计划归属日期
     * @return 是否实际发送及真实目标名称
     */
    public EnergyNotificationResultDto notifyFocus(String memberId, LocalDate date) {
        JsonNode config = read(preferences.find(memberId, CONFIG_KEY));
        JsonNode notification = WorkdayNotificationTargets.resolve(config.path("energyNotification"), "focusTarget");
        String targetName = targetName(notification);
        if (!notification.path("enabled").asBoolean(false)
                || !notification.path("notifyFocus").asBoolean(true)) {
            return new EnergyNotificationResultDto(false, targetName);
        }
        JsonNode stored = read(preferences.find(memberId, DAY_KEY_PREFIX + date));
        JsonNode focus = stored.path("focus");
        if (!focus.isArray() || focus.size() < 3 || focus.size() > 20) {
            throw new BadRequestException("今日重点尚未完整保存，请先重试保存");
        }
        List<String> goals = new java.util.ArrayList<>();
        focus.forEach(value -> {
            String goal = value.isTextual() ? value.asText().trim() : "";
            if (StringUtils.hasText(goal)) goals.add(goal);
        });
        if (goals.size() < 3 || goals.stream().anyMatch(value -> value.length() > 100)) {
            throw new BadRequestException("请至少完整保存三个今日重点后再重试通知");
        }
        List<List<String>> fields = new java.util.ArrayList<>();
        fields.add(List.of("成员", memberName(memberId)));
        fields.add(List.of("日期", date.toString()));
        for (int index = 0; index < goals.size(); index++) {
            fields.add(List.of("重点 " + (index + 1), goals.get(index)));
        }
        FeishuWebhookNotifier.Card card = new FeishuWebhookNotifier.Card(
                "今日重点 · 已承诺", "blue", fields,
                "先完成当前重点，完成后再开始下一项。", "打开今日节奏", links.open("/workday-rhythm"));
        return send(notification, card);
    }

    private EnergyNotificationResultDto send(JsonNode notification, FeishuWebhookNotifier.Card card) {
        if (!notificationSettings.allowsReminders()) {
            throw new BadRequestException(notificationSettings.blockedReason());
        }
        if ("WEBHOOK".equals(notification.path("channel").asText(""))) {
            String webhookId = notification.path("webhookId").asText("").trim();
            if (!StringUtils.hasText(webhookId)) {
                throw new BadRequestException("请先在今日节奏里选择通知 Webhook");
            }
            FeishuWebhookConfigService.NotificationTarget target = webhooks.enabledTarget(webhookId);
            webhookNotifier.send(target.url(), card);
            return new EnergyNotificationResultDto(true,
                    StringUtils.hasText(target.name()) ? target.name() : "已配置 Webhook");
        }

        if (!"CHAT".equals(notification.path("channel").asText("CHAT"))) {
            throw new BadRequestException("通知发送方式无效，请重新选择目标群");
        }
        String chatId = notification.path("chatId").asText("").trim();
        if (!StringUtils.hasText(chatId)) {
            throw new BadRequestException("请先在今日节奏里选择通知群");
        }
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw new BadRequestException("飞书应用尚未配置，请先在自动化的飞书群聊配置中填写应用凭据");
        }

        feishu.sendCard(feishu.tenantAccessToken(appId, appSecret), chatId, card);
        return new EnergyNotificationResultDto(true,
                StringUtils.hasText(targetName(notification)) ? targetName(notification) : "已配置群聊");
    }

    private String targetName(JsonNode notification) {
        return "WEBHOOK".equals(notification.path("channel").asText(""))
                ? notification.path("webhookName").asText("").trim()
                : notification.path("chatName").asText("").trim();
    }

    private FeishuWebhookNotifier.Card card(EnergyAction action, String actionId, LocalDate date, JsonNode day,
            String memberName) {
        int count = persistedCount(day, actionId);
        if (count < 1) {
            throw new BadRequestException("当天「" + action.title() + "」记录尚未保存，请先重试保存");
        }
        String result = "COUNT".equals(action.mode()) ? "已记录 " + count + " 次" : "今日已完成";
        return new FeishuWebhookNotifier.Card(
                "精力补给 · " + action.title() + ("COUNT".equals(action.mode()) ? " +1" : ""),
                "COUNT".equals(action.mode()) ? "blue" : "turquoise",
                List.of(List.of("成员", memberName), List.of("日期", date.toString()),
                        List.of("今日记录", result)),
                "这次行动已由本人在「今日节奏」中记录。", "打开今日节奏", links.open("/workday-rhythm"));
    }

    private EnergyAction action(JsonNode config, String actionId) {
        JsonNode items = config.path("energyItems");
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (actionId.equals(item.path("id").asText())) {
                    String title = item.path("title").asText("").trim();
                    String mode = item.path("mode").asText("");
                    if (!StringUtils.hasText(title) || title.length() > 20
                            || !("COUNT".equals(mode) || "DAILY_ONCE".equals(mode))) {
                        throw new BadRequestException("精力补给项目配置已损坏，请重新保存后再试");
                    }
                    return new EnergyAction(title, mode, item.path("notify").asBoolean(true));
                }
            }
            throw new BadRequestException("精力补给项目不存在或已删除");
        }
        JsonNode notification = config.path("energyNotification");
        if ("WATER".equals(actionId)) {
            return new EnergyAction("喝水", "COUNT", notification.path("notifyWater").asBoolean(true));
        }
        if ("MEDITATION".equals(actionId)) {
            return new EnergyAction("至少冥想一次", "DAILY_ONCE",
                    notification.path("notifyMeditation").asBoolean(true));
        }
        throw new BadRequestException("精力补给项目不存在或已删除");
    }

    private int persistedCount(JsonNode day, String actionId) {
        JsonNode stored = day.path("energyRecords").path(actionId);
        if (stored.isIntegralNumber()) {
            return Math.max(0, stored.asInt());
        }
        if ("WATER".equals(actionId)) {
            return Math.max(0, day.path("waterCount").asInt(0));
        }
        if ("MEDITATION".equals(actionId) && day.path("meditated").asBoolean(false)) {
            return 1;
        }
        return 0;
    }

    private String memberName(String memberId) {
        MemberEntity member = members.selectById(memberId);
        return member != null && StringUtils.hasText(member.getName()) ? member.getName() : memberId;
    }

    private JsonNode read(String raw) {
        if (!StringUtils.hasText(raw)) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(raw);
        } catch (Exception ex) {
            throw new BadRequestException("今日节奏配置已损坏，请重新保存后再试");
        }
    }

    private record EnergyAction(String title, String mode, boolean shouldNotify) { }
}
