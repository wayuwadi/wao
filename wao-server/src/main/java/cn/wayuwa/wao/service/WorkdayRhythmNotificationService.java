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

/** 今日节奏的成员级精力补给通知。群目标来自成员偏好，应用凭据只在服务端读取。 */
@Service
public class WorkdayRhythmNotificationService {

    static final String CONFIG_KEY = "workday-rhythm-config-v1";
    private static final String DAY_KEY_PREFIX = "workday-rhythm-day-";

    private final MemberPreferenceMapper preferences;
    private final MemberMapper members;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuAppLinks links;
    private final ObjectMapper json;

    public WorkdayRhythmNotificationService(MemberPreferenceMapper preferences, MemberMapper members,
            SettingService settings, FeishuOpenApiClient feishu, FeishuAppLinks links, ObjectMapper json) {
        this.preferences = preferences;
        this.members = members;
        this.settings = settings;
        this.feishu = feishu;
        this.links = links;
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
        JsonNode notification = read(preferences.find(memberId, CONFIG_KEY)).path("energyNotification");
        String chatName = notification.path("chatName").asText("").trim();
        if (!notification.path("enabled").asBoolean(false)
                || !notification.path(flagFor(request.action())).asBoolean(true)) {
            return new EnergyNotificationResultDto(false, chatName);
        }

        String chatId = notification.path("chatId").asText("").trim();
        if (!StringUtils.hasText(chatId)) {
            throw new BadRequestException("请先在今日节奏里选择精力补给通知群");
        }
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw new BadRequestException("飞书应用尚未配置，请先在自动化的飞书群聊配置中填写应用凭据");
        }

        JsonNode day = read(preferences.find(memberId, DAY_KEY_PREFIX + request.date()));
        String memberName = memberName(memberId);
        FeishuWebhookNotifier.Card card = card(request.action(), request.date(), day, memberName);
        feishu.sendCard(feishu.tenantAccessToken(appId, appSecret), chatId, card);
        return new EnergyNotificationResultDto(true,
                StringUtils.hasText(chatName) ? chatName : "已配置群聊");
    }

    private FeishuWebhookNotifier.Card card(String action, LocalDate date, JsonNode day, String memberName) {
        if ("WATER".equals(action)) {
            int count = day.path("waterCount").asInt(0);
            if (count < 1) {
                throw new BadRequestException("当天喝水记录尚未保存，请先重试保存");
            }
            return new FeishuWebhookNotifier.Card(
                    "精力补给 · 喝水 +1", "blue",
                    List.of(List.of("成员", memberName), List.of("日期", date.toString()),
                            List.of("今日记录", "已喝水 " + count + " 次")),
                    "这次行动已由本人在「今日节奏」中记录。", "打开今日节奏", links.open("/workday-rhythm"));
        }
        if (!day.path("meditated").asBoolean(false)) {
            throw new BadRequestException("当天冥想记录尚未保存，请先重试保存");
        }
        return new FeishuWebhookNotifier.Card(
                "精力补给 · 冥想一下", "turquoise",
                List.of(List.of("成员", memberName), List.of("日期", date.toString()),
                        List.of("今日记录", "已完成至少一次冥想")),
                "这次行动已由本人在「今日节奏」中记录。", "打开今日节奏", links.open("/workday-rhythm"));
    }

    private String memberName(String memberId) {
        MemberEntity member = members.selectById(memberId);
        return member != null && StringUtils.hasText(member.getName()) ? member.getName() : memberId;
    }

    private String flagFor(String action) {
        return "WATER".equals(action) ? "notifyWater" : "notifyMeditation";
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
}
