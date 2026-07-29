package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.domain.AccountEntity;
import cn.wayuwa.wao.domain.AccountIdentityEntity;
import cn.wayuwa.wao.domain.ProjectEntity;
import cn.wayuwa.wao.dto.FeishuQuickCollectConfigDto;
import cn.wayuwa.wao.dto.SaveQuickCollectConfigRequest;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import cn.wayuwa.wao.mapper.ProjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 群聊快速收集：在飞书群里 @机器人 说一句话，WAO 直接把它变成一条任务。
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
    /** 默认落点项目名（用户指定的第一个项目）。 */
    private static final String DEFAULT_PROJECT_NAME = "Wa All in One";
    /** 标题最多取多少字，超出的部分留在描述里。 */
    private static final int TITLE_LIMIT = 40;
    /** 最近处理过的事件 id：飞书会重推，重推不该变成两条任务。 */
    private static final int SEEN_EVENTS = 500;

    private static final Logger log = LoggerFactory.getLogger(FeishuQuickCollectService.class);

    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final FeishuWebhookNotifier notifier;
    private final FeishuAppLinks links;
    private final TaskService tasks;
    private final ProjectMapper projects;
    private final AccountMapper accounts;
    private final AccountIdentityMapper identities;
    private final ObjectMapper json;
    private final NotificationFeedbackService feedback;
    private final Set<String> seenEvents = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_EVENTS;
                }
            }));

    public FeishuQuickCollectService(SettingService settings, FeishuOpenApiClient feishu,
            FeishuWebhookNotifier notifier, FeishuAppLinks links, TaskService tasks, ProjectMapper projects,
            AccountMapper accounts, AccountIdentityMapper identities, ObjectMapper json, NotificationFeedbackService feedback) {
        this.settings = settings;
        this.feishu = feishu;
        this.notifier = notifier;
        this.links = links;
        this.tasks = tasks;
        this.projects = projects;
        this.accounts = accounts;
        this.identities = identities;
        this.json = json;
        this.feedback = feedback;
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
                mentionOpenIds());
    }

    /**
     * 保存配置。密文字段传 null 表示保持原值（前端密码框留空即不改）。
     *
     * @param req 配置表单
     * @return 保存后的配置（脱敏）
     */
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
        JsonNode body = decrypt(rawBody);
        if ("url_verification".equals(body.path("type").asText(""))) {
            requireToken(body.path("token").asText(""));
            return Map.of("challenge", body.path("challenge").asText(""));
        }
        requireToken(body.path("header").path("token").asText(body.path("token").asText("")));
        if ("card.action.trigger".equals(body.path("header").path("event_type").asText(""))) {
            feedback.collect(body.path("event"));
            return Map.of();
        }
        if (!settings.getBoolean(SettingService.QUICK_COLLECT_ENABLED)) {
            return Map.of();
        }
        String eventId = body.path("header").path("event_id").asText("");
        if (StringUtils.hasText(eventId) && !seenEvents.add(eventId)) {
            return Map.of();
        }
        if (!"im.message.receive_v1".equals(body.path("header").path("event_type").asText(""))) {
            return Map.of();
        }
        try {
            collect(body.path("event"));
        } catch (RuntimeException ex) {
            // 事件已经收下了，重推同一条只会再失败一次；把原因留在日志里，别把 5xx 抛回飞书
            log.warn("群聊快速收集处理失败：{}", ex.getMessage());
        }
        return Map.of();
    }

    /** 真正把一条群消息变成任务。 */
    private void collect(JsonNode event) {
        JsonNode message = event.path("message");
        String chatId = message.path("chat_id").asText("");
        String configured = settings.get(SettingService.CHAT_SYNC_CHAT_ID, "");
        if (StringUtils.hasText(configured) && !configured.equals(chatId)) {
            return;
        }
        String type = message.path("message_type").asText("");
        if ("audio".equals(type)) {
            reply(chatId, FeishuWebhookNotifier.Card.simple("这条语音我记不下来", "orange",
                    "请长按语音条用飞书的「转文字」发一遍，或者直接用语音输入发文字，我就能建任务了。"));
            return;
        }
        String text = readableText(type, message);
        if (!StringUtils.hasText(text)) {
            return;
        }
        ProjectEntity project = targetProject();
        if (project == null) {
            reply(chatId, FeishuWebhookNotifier.Card.simple("还没有可用的项目", "red",
                    "请先在 WAO 里建一个项目，或在「设置 → 飞书」里指定快速收集的落点项目。"));
            return;
        }
        TaskDto task = tasks.createFromChat(project.getId(), title(text), text,
                senderMemberId(event.path("sender")), SOURCE_TAG);
        log.info("群聊快速收集：已创建任务 {}（项目 {}）", task.id(), project.getId());
        reply(chatId, new FeishuWebhookNotifier.Card(
                "想法已记进 WAO",
                "blue",
                List.of(
                        List.of("项目", project.getName()),
                        List.of("任务", task.title()),
                        List.of("标签", SOURCE_TAG)),
                "点开就能补充细节；想让大家一起补，把状态改成「" + TaskService.COLLECTING + "」会自动分享回群里。",
                "打开任务",
                links.taskUrl(project.getId(), task.id())));
    }

    /** 把消息内容读成一行可用的文字，并去掉 @ 机器人留下的占位符。 */
    private String readableText(String type, JsonNode message) {
        JsonNode content = read(message.path("content").asText("{}"));
        String text = switch (type) {
            case "text" -> content.path("text").asText("");
            case "post" -> postText(content);
            default -> "";
        };
        for (JsonNode mention : message.path("mentions")) {
            String key = mention.path("key").asText("");
            if (StringUtils.hasText(key)) {
                text = text.replace(key, "");
            }
        }
        return text.trim();
    }

    /** 富文本消息：把段落里的文字拼起来。 */
    private static String postText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        String title = content.path("title").asText("");
        if (StringUtils.hasText(title)) {
            sb.append(title).append('\n');
        }
        for (JsonNode line : content.path("content")) {
            for (JsonNode span : line) {
                if ("text".equals(span.path("tag").asText(""))) {
                    sb.append(span.path("text").asText(""));
                } else if ("a".equals(span.path("tag").asText(""))) {
                    sb.append(span.path("text").asText("")).append(' ')
                            .append(span.path("href").asText(""));
                }
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** 标题取第一行；一行太长就截断，全文仍然留在描述里。 */
    static String title(String text) {
        String first = text.lines().map(String::trim).filter(StringUtils::hasText).findFirst().orElse(text.trim());
        return first.length() <= TITLE_LIMIT ? first : first.substring(0, TITLE_LIMIT) + "…";
    }

    /** 落点项目：先看配置，其次按名字找「Wa All in One」，最后退回第一个项目。 */
    private ProjectEntity targetProject() {
        String configured = settings.get(SettingService.QUICK_COLLECT_PROJECT_ID, "");
        if (StringUtils.hasText(configured)) {
            ProjectEntity project = projects.selectById(configured);
            if (project != null) {
                return project;
            }
        }
        List<ProjectEntity> all = projects.selectList(null);
        return all.stream().filter(p -> DEFAULT_PROJECT_NAME.equalsIgnoreCase(p.getName())).findFirst()
                .orElseGet(() -> all.isEmpty() ? null : all.get(0));
    }

    /** 发言人对应的 WAO 成员；没绑过飞书的人退回默认负责人。 */
    private String senderMemberId(JsonNode sender) {
        String openId = sender.path("sender_id").path("open_id").asText("");
        if (StringUtils.hasText(openId)) {
            AccountIdentityEntity identity = identities.findByProviderSubject("feishu", openId);
            if (identity != null) {
                AccountEntity account = accounts.selectById(identity.getAccountId());
                if (account != null && StringUtils.hasText(account.getMemberId())) {
                    return account.getMemberId();
                }
            }
        }
        return "wg";
    }

    /** 回执优先回到事件发生的那个群；应用凭据没配好时退回全局 webhook。 */
    private void reply(String chatId, FeishuWebhookNotifier.Card card) {
        try {
            String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
            String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
            if (StringUtils.hasText(appId) && StringUtils.hasText(appSecret) && StringUtils.hasText(chatId)) {
                feishu.sendCard(feishu.tenantAccessToken(appId, appSecret), chatId, card);
                return;
            }
        } catch (RuntimeException ex) {
            log.warn("群聊快速收集回执发送失败，回落 webhook：{}", ex.getMessage());
        }
        notifier.notifyCard(card);
    }

    /** 配置过 Verification Token 时才校验；没配就跳过（飞书那边也可以不开）。 */
    private void requireToken(String presented) {
        String expected = settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN);
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
}
