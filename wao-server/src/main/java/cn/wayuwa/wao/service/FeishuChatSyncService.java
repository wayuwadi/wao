package cn.wayuwa.wao.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.DocBlockDto;
import cn.wayuwa.wao.dto.DocDto;
import cn.wayuwa.wao.dto.FeishuChatSyncConfigDto;
import cn.wayuwa.wao.dto.FeishuSenderDto;
import cn.wayuwa.wao.dto.ImageItemDto;
import cn.wayuwa.wao.dto.SaveChatSyncConfigRequest;
import cn.wayuwa.wao.feishu.FeishuApiException;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChat;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuChatMember;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient.FeishuMessage;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 飞书群聊消息同步知识库：每天 23:59 把当天 00:00–23:59 的群聊记录沉淀成一篇文档。
 *
 * <p>一天一篇，标题就是那一天（{@code 飞书群聊 · 2026-07-25}），同一天重复运行覆盖同一篇，
 * 不会在知识库里堆同名文档。发言人按配置里的 ID → 人名映射还原（娃哥 / 娃弟 / 机器人通知），
 * 没映射到的退回飞书昵称、再不行就留个短 ID，方便回头补映射。
 *
 * <p>群聊里的图片先下载下来转存本地图床，文档里引用的是图床地址：飞书的图片资源地址要带
 * 令牌才能访问，直接引用等于第二天就打不开了。
 */
@Service
public class FeishuChatSyncService implements JobHandler {

    /** 自动化任务里登记的执行器名（{@code automation_job.handler}）。 */
    public static final String HANDLER = "feishu-chat-sync";

    private static final Logger log = LoggerFactory.getLogger(FeishuChatSyncService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final TypeReference<List<FeishuSenderDto>> SENDERS_TYPE = new TypeReference<>() { };
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final KnowledgeService knowledge;
    private final ImageService images;
    private final FeishuWebhookNotifier notifier;
    private final ObjectMapper json;

    public FeishuChatSyncService(SettingService settings, FeishuOpenApiClient feishu, KnowledgeService knowledge,
            ImageService images, FeishuWebhookNotifier notifier, ObjectMapper json) {
        this.settings = settings;
        this.feishu = feishu;
        this.knowledge = knowledge;
        this.images = images;
        this.notifier = notifier;
        this.json = json;
    }

    @Override
    public String name() {
        return HANDLER;
    }

    /** 定时 / 手动触发都走这里：同步「今天」的群聊记录。 */
    @Override
    public String execute() {
        return syncDay(null);
    }

    /** 定时执行严格同步本次 Cron 的计划日期；手动执行仍同步上海时区的今天。 */
    @Override
    public String execute(cn.wayuwa.wao.domain.AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        return syncDay(scheduledFireAt == null ? null : scheduledFireAt.toLocalDate());
    }

    /** 当前配置（App Secret 只回脱敏形态）。 */
    public FeishuChatSyncConfigDto config() {
        String secret = settings.get(SettingService.CHAT_SYNC_APP_SECRET);
        return new FeishuChatSyncConfigDto(
                settings.get(SettingService.CHAT_SYNC_APP_ID, ""),
                SettingService.mask(secret),
                secret != null && !secret.isBlank(),
                settings.get(SettingService.CHAT_SYNC_CHAT_ID, ""),
                settings.get(SettingService.CHAT_SYNC_CHAT_NAME, ""),
                settings.get(SettingService.CHAT_SYNC_KB_ID, ""),
                senders());
    }

    /**
     * 保存配置。App Secret 传 null 表示保持原值（前端密码框留空即不改）。
     *
     * @param req 配置表单
     * @return 保存后的配置（脱敏）
     */
    public FeishuChatSyncConfigDto saveConfig(SaveChatSyncConfigRequest req) {
        settings.put(SettingService.CHAT_SYNC_APP_ID, trimmed(req.appId()));
        settings.put(SettingService.CHAT_SYNC_APP_SECRET, trimmed(req.appSecret()));
        settings.put(SettingService.CHAT_SYNC_CHAT_ID, trimmed(req.chatId()));
        settings.put(SettingService.CHAT_SYNC_CHAT_NAME, trimmed(req.chatName()));
        settings.put(SettingService.CHAT_SYNC_KB_ID, trimmed(req.kbId()));
        if (req.senders() != null) {
            settings.put(SettingService.CHAT_SYNC_SENDERS, write(req.senders()));
        }
        return config();
    }

    /**
     * 列出机器人所在的群，供配置页选择目标群。
     *
     * @return 群列表
     * @throws BadRequestException 还没填应用凭据时
     */
    public List<FeishuChat> chats() {
        return feishu.listChats(token());
    }

    /**
     * 列出目标群的成员，并套上已有的人名映射，供配置页把 ID 对上人。
     *
     * @param chatId 群 id；为空时用配置里的群
     * @return 成员列表（已按现有映射覆盖显示名）
     */
    public List<FeishuSenderDto> members(String chatId) {
        String target = chatId == null || chatId.isBlank() ? settings.get(SettingService.CHAT_SYNC_CHAT_ID) : chatId;
        if (target == null || target.isBlank()) {
            throw new BadRequestException("请先选择要同步的群聊");
        }
        Map<String, String> mapped = senderNames();
        List<FeishuSenderDto> out = new ArrayList<>();
        for (FeishuChatMember m : feishu.listChatMembers(token(), target)) {
            out.add(new FeishuSenderDto(m.openId(), mapped.getOrDefault(m.openId(), m.name())));
        }
        return out;
    }

    /**
     * 跑一次同步：把某一天的群聊记录写成知识库里的一篇文档。
     *
     * @param day 要同步的日期；null 表示今天
     * @return 本次同步的结果摘要，直接作为自动化运行记录的文案
     * @throws BadRequestException 配置不完整时
     * @throws FeishuApiException  调用飞书失败时
     */
    public String syncDay(LocalDate day) {
        LocalDate target = day == null ? LocalDate.now(ZONE) : day;
        String chatId = required(SettingService.CHAT_SYNC_CHAT_ID, "请先选择要同步的群聊");
        String kbId = required(SettingService.CHAT_SYNC_KB_ID, "请先选择沉淀到哪个知识库");
        String monthFolder = target.getYear() + " 年 " + target.getMonthValue() + " 月";
        String folderId = knowledge.ensureAutoFolder(kbId, monthFolder).getId();

        // 当天 00:00:00 到 23:59:59，与「截止到今天早上零点到今天晚上 23:59」一致
        long start = target.atStartOfDay(ZONE).toEpochSecond();
        long end = target.atTime(LocalTime.of(23, 59, 59)).atZone(ZONE).toEpochSecond();

        String token = token();
        List<FeishuMessage> messages = feishu.listMessages(token, chatId, start, end);
        Map<String, String> names = senderNames();
        List<DocBlockDto> blocks = render(token, target, messages, names);

        String title = "飞书群聊 · " + target.format(DAY);
        DocDto doc = knowledge.upsertAutoDoc(kbId, folderId, title, blocks);
        log.info("飞书群聊同步完成：{} 条消息写入文档 {}", messages.size(), doc.id());
        notifier.notifyCard(new FeishuWebhookNotifier.Card(
                "群聊记录已沉淀",
                "green",
                List.of(
                        List.of("日期", target.format(DAY) + " 00:00–23:59"),
                        List.of("群聊", settings.get(SettingService.CHAT_SYNC_CHAT_NAME, "群聊")),
                        List.of("消息", messages.size() + " 条")),
                null,
                "打开文档",
                "https://wao.wayuwa.cn/kb/" + kbId + "?doc=" + doc.id()));
        return "成功 · " + target.format(DAY) + " 共 " + messages.size() + " 条消息";
    }

    /**
     * 按天补录一段连续日期的群聊历史。
     *
     * <p>每一天沿用 {@link #syncDay(LocalDate)} 的覆盖规则，因此同一天不会产生重复文档；
     * 任意一天失败会立即停止，避免把失败伪装成完整补录。
     *
     * @param start 起始日期（含）
     * @param end 结束日期（含）
     * @return 便于界面展示的汇总结果
     */
    public String syncDays(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new BadRequestException("起始日期不能晚于结束日期");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (days > 366) {
            throw new BadRequestException("一次最多同步 366 天，请缩小历史范围后重试");
        }
        int messages = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            String result = syncDay(day);
            int marker = result.indexOf(" 共 ");
            int suffix = result.indexOf(" 条消息");
            if (marker >= 0 && suffix > marker) {
                messages += Integer.parseInt(result.substring(marker + 3, suffix));
            }
        }
        return "成功 · 已同步 " + start.format(DAY) + " 至 " + end.format(DAY)
                + "，共 " + days + " 天、" + messages + " 条消息";
    }

    /** 正文：一条消息一段，图片单独成段，按小时分节。 */
    private List<DocBlockDto> render(String token, LocalDate day, List<FeishuMessage> messages,
            Map<String, String> names) {
        List<DocBlockDto> blocks = new ArrayList<>();
        blocks.add(block(day.format(DAY) + " 00:00–23:59 的群聊记录，由自动化每天 23:59 同步，"
                + "手动编辑会在下次同步时被覆盖。", null, null, null));
        if (messages.isEmpty()) {
            blocks.add(paragraph("这一天群里没有消息。"));
            return blocks;
        }
        String currentHour = "";
        for (FeishuMessage m : messages) {
            if (m.deleted()) {
                continue;
            }
            String time = Instant.ofEpochMilli(m.createTime()).atZone(ZONE).format(CLOCK);
            String hour = time.substring(0, 2) + ":00";
            if (!hour.equals(currentHour)) {
                currentHour = hour;
                blocks.add(heading(hour));
            }
            String who = names.getOrDefault(m.senderId(), shortId(m.senderId()));
            String text = textOf(m);
            if (!text.isBlank()) {
                blocks.add(paragraph("**" + time + " " + who + "**：" + text));
            }
            for (String url : imageUrls(token, m)) {
                blocks.add(paragraph("![群聊图片](" + url + ")"));
            }
        }
        return blocks;
    }

    /** 把一条消息的 content JSON 渲染成一行纯文本；不认识的类型至少标出类型名。 */
    private String textOf(FeishuMessage m) {
        JsonNode content = readContent(m);
        return switch (m.msgType()) {
            case "text" -> content.path("text").asText("");
            case "image" -> "";
            case "post" -> postText(content);
            case "interactive" -> interactiveText(content);
            case "file" -> "[文件] " + content.path("file_name").asText("");
            case "audio" -> "[语音]";
            case "media" -> "[视频] " + content.path("file_name").asText("");
            case "sticker" -> "[表情]";
            case "share_chat" -> "[分享群聊] " + content.path("chat_name").asText("");
            default -> "[" + m.msgType() + "]";
        };
    }

    /** 富文本消息：把所有段落里的文字拼起来，链接保留成 Markdown。 */
    private String postText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        JsonNode title = content.path("title");
        if (title.isTextual() && !title.asText().isBlank()) {
            sb.append(title.asText()).append(' ');
        }
        for (JsonNode line : content.path("content")) {
            for (JsonNode span : line) {
                String tag = span.path("tag").asText("");
                if ("text".equals(tag)) {
                    sb.append(span.path("text").asText(""));
                } else if ("a".equals(tag)) {
                    sb.append('[').append(span.path("text").asText("")).append("](")
                            .append(span.path("href").asText("")).append(')');
                } else if ("at".equals(tag)) {
                    sb.append('@').append(span.path("user_name").asText(""));
                }
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /** 将机器人 interactive 卡片提取为可读文本，避免知识库里只留下「[interactive]」。 */
    private String interactiveText(JsonNode content) {
        List<String> parts = new ArrayList<>();
        String title = content.path("header").path("title").path("content").asText("");
        if (!title.isBlank()) {
            parts.add(title);
        }
        for (JsonNode element : content.path("elements")) {
            String text = element.path("text").path("content").asText("");
            if (!text.isBlank()) {
                parts.add(text.replaceAll("[*_`]+", "").replace("\n", " "));
            }
            for (JsonNode field : element.path("fields")) {
                String fieldText = field.path("text").path("content").asText("");
                if (!fieldText.isBlank()) {
                    parts.add(fieldText.replaceAll("[*_`]+", "").replace("\n", " "));
                }
            }
        }
        return parts.isEmpty() ? "[机器人卡片]" : String.join(" · ", parts);
    }

    /** 消息里的图片：下载后转存图床，返回本地地址；下载或转存失败就跳过这一张。 */
    private List<String> imageUrls(String token, FeishuMessage m) {
        List<String> keys = new ArrayList<>();
        JsonNode content = readContent(m);
        if ("image".equals(m.msgType())) {
            String key = content.path("image_key").asText("");
            if (!key.isBlank()) {
                keys.add(key);
            }
        } else if ("post".equals(m.msgType())) {
            for (JsonNode line : content.path("content")) {
                for (JsonNode span : line) {
                    if ("img".equals(span.path("tag").asText(""))) {
                        String key = span.path("image_key").asText("");
                        if (!key.isBlank()) {
                            keys.add(key);
                        }
                    }
                }
            }
        }
        List<String> urls = new ArrayList<>();
        for (String key : keys) {
            byte[] bytes = feishu.downloadImage(token, m.messageId(), key);
            if (bytes == null || bytes.length == 0) {
                log.warn("飞书群聊图片下载失败，已跳过：message={} key={}", m.messageId(), key);
                continue;
            }
            try {
                // 补录 / 重跑同一天时会再抓一遍同样的图，按内容去重，不在图床里堆重复副本。
                ImageItemDto stored = images.store(bytes, "image/png", key + ".png",
                        "飞书群聊 · " + settings.get(SettingService.CHAT_SYNC_CHAT_NAME, "群聊"), true);
                urls.add(stored.url());
            } catch (RuntimeException ex) {
                log.warn("飞书群聊图片转存图床失败，已跳过：{}", ex.getMessage());
            }
        }
        return urls;
    }

    private JsonNode readContent(FeishuMessage m) {
        try {
            return json.readTree(m.content() == null || m.content().isBlank() ? "{}" : m.content());
        } catch (Exception ex) {
            return json.createObjectNode();
        }
    }

    /** 已配置的发言人映射。 */
    public List<FeishuSenderDto> senders() {
        String raw = settings.get(SettingService.CHAT_SYNC_SENDERS);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, SENDERS_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, String> senderNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (FeishuSenderDto s : senders()) {
            if (s.id() != null && !s.id().isBlank() && s.name() != null && !s.name().isBlank()) {
                out.put(s.id().trim(), s.name().trim());
            }
        }
        return out;
    }

    private String token() {
        String appId = required(SettingService.CHAT_SYNC_APP_ID, "请先填写飞书应用的 App ID");
        String appSecret = required(SettingService.CHAT_SYNC_APP_SECRET, "请先填写飞书应用的 App Secret");
        return feishu.tenantAccessToken(appId, appSecret);
    }

    private String required(String key, String message) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
        return value.trim();
    }

    /** 没映射上的发言人：留一段可辨识的 ID，方便回配置页补上。 */
    private static String shortId(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return "未知成员";
        }
        return senderId.length() <= 10 ? senderId : "成员 " + senderId.substring(senderId.length() - 6);
    }

    private static DocBlockDto paragraph(String text) {
        return block(null, null, text, null);
    }

    private static DocBlockDto heading(String text) {
        return block(null, text, null, null);
    }

    private static DocBlockDto block(String call, String h, String p, List<String> ul) {
        return new DocBlockDto(call, h, p, ul, null);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }
}
