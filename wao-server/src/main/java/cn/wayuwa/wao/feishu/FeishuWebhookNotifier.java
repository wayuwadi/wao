package cn.wayuwa.wao.feishu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.service.SettingService;
import cn.wayuwa.wao.service.FeishuWebhookConfigService;
import cn.wayuwa.wao.service.NotificationSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 飞书群机器人通知：系统既有通知统一走通用注册表中标记为默认的 webhook。
 *
 * <p>地址由用户在「设置 → Webhook配置」里维护，加密存 {@code feishu_webhook_config}；
 * {@code system_setting} 中的旧单例只用于滚动发布兼容，因此既不进 Git 也不必改环境变量重启。
 */
@Component
public class FeishuWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookNotifier.class);

    @Autowired
    private cn.wayuwa.wao.service.InternalNotificationService internalNotifications;

    private final SettingService settings;
    private final FeishuWebhookConfigService webhooks;
    private final NotificationSettingsService notificationSettings;
    private final RestClient client;
    private final RestClient rawCardClient;
    /** 老的部署级 webhook（{@code FEISHU_COMMENT_WEBHOOK_URL}）：界面上还没配时继续用它，线上通知不断档。 */
    private final String legacyUrl;

    @Autowired
    public FeishuWebhookNotifier(SettingService settings, FeishuWebhookConfigService webhooks,
            NotificationSettingsService notificationSettings, RestClient.Builder builder,
            @Value("${feishu.comment-webhook-url:}") String legacyUrl) {
        this(settings, webhooks, notificationSettings, builder, legacyUrl, rawCardRequestFactory());
    }

    FeishuWebhookNotifier(SettingService settings, FeishuWebhookConfigService webhooks,
            NotificationSettingsService notificationSettings, RestClient.Builder builder,
            String legacyUrl, ClientHttpRequestFactory requestFactory) {
        this.settings = settings;
        this.webhooks = webhooks;
        this.notificationSettings = notificationSettings;
        this.client = builder.build();
        RestClient.Builder rawBuilder = builder.clone();
        if (requestFactory != null) rawBuilder.requestFactory(requestFactory);
        this.rawCardClient = rawBuilder.build();
        this.legacyUrl = legacyUrl;
    }

    private static ClientHttpRequestFactory rawCardRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(10));
        factory.setReadTimeout(java.time.Duration.ofSeconds(30));
        return factory;
    }

    /** 实际要用的地址：优先注册表默认项；尚未接管时才回落旧单例或部署环境变量。 */
    public String resolvedUrl() {
        return target().url();
    }

    /**
     * 通知是否可用。
     *
     * <p>界面上配过地址时以启用开关为准；只有环境变量兜底时视为启用 —— 那是老部署的既有行为，
     * 不该因为这次多了个开关就悄悄停掉。
     */
    public boolean enabled() {
        return target().enabled();
    }

    /**
     * 按当前配置发一张卡片通知；没配置或未启用时直接跳过。
     *
     * @param card 卡片内容
     * @return 是否真的发出去了
     */
    public boolean notifyCard(Card card) {
        if (!notificationSettings.allows(policy -> true)) return false;
        try { return internalNotifications.publish(card); }
        catch (RuntimeException ex) {
            log.warn("WAO 内部通知保存失败");
            return false;
        }
    }

    /**
     * 将重置卡增加事件投递到现有默认飞书群；其他系统通知仍保留站内路由。
     * @param card 已确认的重置卡增加通知
     * @return 飞书明确确认接收时为 true；关闭、缺少配置或失败时为 false，供调用方保留基线重试
     */
    public boolean notifyResetCardIncrease(Card card) {
        if (!notificationSettings.allows(policy -> policy.automation())) return false;
        try {
            EffectiveTarget destination = target();
            if (!destination.enabled() || destination.url() == null || destination.url().isBlank()) return false;
            JsonNode response = rawCardClient.post().uri(destination.url()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "interactive", "card", card.toPayload()))
                    .retrieve().body(JsonNode.class);
            boolean accepted = response != null && (response.has("code") || response.has("StatusCode"))
                    && response.path("code").asInt(response.path("StatusCode").asInt(-1)) == 0;
            if (!accepted) log.warn("重置卡增加通知未获飞书确认，保留基线等待重试");
            return accepted;
        } catch (RuntimeException ex) {
            // 不记录异常正文，避免 HTTP 错误包含 Webhook 凭据。
            log.warn("重置卡增加通知投递失败，保留基线等待重试：{}", ex.getClass().getSimpleName());
            return false;
        }
    }

    /** 原群目标已退休，所有系统通知进入 WAO 通知中心。 */
    public boolean notifyCard(String webhookId, Card card) {
        return notifyCard(card);
    }

    /** 漫画通知的标题、正文、重点与链接以站内通知保存。 */
    public boolean notifyComicCard(ComicCard card) {
        String body = (card.headline() == null ? "" : card.headline()) + "\n"
                + (card.highlights() == null ? "" : String.join("\n", card.highlights())) + "\n"
                + (card.body() == null ? "" : card.body());
        return notifyCard(new Card(card.title(), card.theme(), card.fields(), body, card.linkText(), card.linkUrl()));
    }

    private EffectiveTarget target() {
        var configuredTarget = webhooks.defaultTarget();
        if (configuredTarget.isPresent()) {
            var configured = configuredTarget.get();
            return new EffectiveTarget(configured.url(), configured.enabled());
        }
        if (webhooks.registryInitialized()) return new EffectiveTarget("", false);
        String configured = settings.get(SettingService.FEISHU_WEBHOOK_URL);
        if (configured != null && !configured.isBlank()) {
            return new EffectiveTarget(configured, settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED));
        }
        return new EffectiveTarget(legacyUrl, legacyUrl != null && !legacyUrl.isBlank());
    }

    /** 向业务已校验的指定 Webhook 发送 JSON 2.0 卡片；不回落默认群。 */
    public void sendRawCard(String url, JsonNode card) {
        if (url == null || url.isBlank()) throw new FeishuApiException("未选择通知 Webhook");
        JsonNode response;
        try {
            response = rawCardClient.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "interactive", "card", card)).retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new FeishuApiException("未能确认飞书卡片接收结果");
        }
        if (response == null || (!response.has("code") && !response.has("StatusCode"))
                || response.path("code").asInt(response.path("StatusCode").asInt(-1)) != 0) {
            throw new FeishuApiException("飞书未确认接收卡片，请检查图片及机器人配置");
        }
    }

    private record EffectiveTarget(String url, boolean enabled) {
    }

    /**
     * 往指定 webhook 发一张卡片（「发送测试消息」按钮直接用它，不受启用开关影响）。
     *
     * @param url  webhook 地址
     * @param card 卡片内容
     * @throws FeishuApiException 地址为空、网络不通或飞书返回错误码时
     */
    public void send(String url, Card card) {
        if (url == null || url.isBlank()) {
            throw new FeishuApiException("请先填写飞书 webhook 地址");
        }
        JsonNode res;
        try {
            res = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "interactive", "card", card.toPayload()))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new FeishuApiException("无法连接飞书 webhook，请检查地址是否正确", ex);
        }
        int code = res == null ? -1 : res.path("code").asInt(res.path("StatusCode").asInt(0));
        if (code != 0) {
            throw new FeishuApiException("飞书 webhook 返回错误：" + (res == null ? "空响应" : res.path("msg").asText("")));
        }
    }

    /**
     * 以业务显式选择的自定义机器人发送普通文本。
     *
     * <p>这是用户主动 @ 机器人的直接答复，不受自动通知静默策略影响；目标仍必须存在且启用。
     * Webhook 地址只在本方法内解密使用，不返回给调用方。
     *
     * @param webhookId 通用 Webhook 注册表稳定 id
     * @param text 要直接显示在群里的文字
     */
    public void sendText(String webhookId, String text) {
        if (webhookId == null || webhookId.isBlank()) {
            throw new FeishuApiException("请选择飞书 webhook 配置");
        }
        FeishuWebhookConfigService.NotificationTarget target = webhooks.enabledTarget(webhookId.trim());
        sendTextToUrl(target.url(), text);
    }

    private void sendTextToUrl(String url, String text) {
        if (url == null || url.isBlank()) {
            throw new FeishuApiException("请先填写飞书 webhook 地址");
        }
        if (text == null || text.isBlank()) {
            throw new FeishuApiException("飞书文本消息不能为空");
        }
        JsonNode res;
        try {
            res = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "text", "content", Map.of("text", text)))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new FeishuApiException("无法连接飞书 webhook，请检查地址是否正确", ex);
        }
        int code = res == null ? -1 : res.path("code").asInt(res.path("StatusCode").asInt(0));
        if (code != 0) {
            throw new FeishuApiException("飞书 webhook 返回错误："
                    + (res == null ? "空响应" : res.path("msg").asText("")));
        }
    }

    /**
     * 往指定 webhook 发一张漫画风格卡片。
     *
     * @param url  webhook 地址
     * @param card 漫画卡片内容
     * @throws FeishuApiException 地址为空、网络不通或飞书返回错误码时
     */
    public void sendComic(String url, ComicCard card) {
        if (url == null || url.isBlank()) {
            throw new FeishuApiException("请先填写飞书 webhook 地址");
        }
        JsonNode res;
        try {
            res = client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("msg_type", "interactive", "card", card.toPayload()))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new FeishuApiException("无法连接飞书 webhook，请检查地址是否正确", ex);
        }
        int code = res == null ? -1 : res.path("code").asInt(res.path("StatusCode").asInt(0));
        if (code != 0) {
            throw new FeishuApiException("飞书 webhook 返回错误：" + (res == null ? "空响应" : res.path("msg").asText("")));
        }
    }

    /**
     * 一张飞书消息卡片。
     *
     * <p>纯文本通知在群里就是一坨灰字，标题、正文、链接全糊在一起。卡片有标题栏配色、
     * 字段分行和跳转按钮，一眼能看出是什么事、要不要点进去。
     *
     * @param title  卡片标题
     * @param theme  标题栏配色（{@code blue} / {@code green} / {@code orange} / {@code red} / {@code grey}）
     * @param fields 正文字段，按 [名称, 值] 逐行展示；值支持 Markdown
     * @param body   补充正文（Markdown），可为空
     * @param linkText 底部按钮文案，为空则不加按钮
     * @param linkUrl  底部按钮跳转地址
     */
    public record Card(String title, String theme, List<List<String>> fields, String body,
                       String linkText, String linkUrl) {

        /** 只有标题和正文的简单卡片。 */
        public static Card simple(String title, String theme, String body) {
            return new Card(title, theme, List.of(), body, null, null);
        }

        /** 组装成飞书 interactive 卡片的 JSON 结构。 */
        Map<String, Object> toPayload() {
            List<Map<String, Object>> elements = new ArrayList<>();
            for (List<String> field : fields) {
                if (field.size() >= 2 && field.get(1) != null && !field.get(1).isBlank()) {
                    elements.add(markdown("**" + field.get(0) + "**：" + field.get(1)));
                }
            }
            if (body != null && !body.isBlank()) {
                if (!elements.isEmpty()) {
                    elements.add(Map.of("tag", "hr"));
                }
                elements.add(markdown(body));
            }
            if (linkText != null && !linkText.isBlank() && linkUrl != null && !linkUrl.isBlank()) {
                elements.add(Map.of("tag", "action", "actions", List.of(Map.of(
                        "tag", "button",
                        "text", Map.of("tag", "plain_text", "content", linkText),
                        "type", "primary",
                        "url", linkUrl))));
            }
            if (elements.isEmpty()) {
                elements.add(markdown(" "));
            }
            return Map.of(
                    "config", Map.of("wide_screen_mode", true),
                    "header", Map.of(
                            "template", theme == null || theme.isBlank() ? "blue" : theme,
                            "title", Map.of("tag", "plain_text", "content", title)),
                    "elements", elements);
        }

        private static Map<String, Object> markdown(String content) {
            return Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", content));
        }
    }

    /**
     * 漫画风格消息卡片：用插图、大标题、醒目要点把核心信息第一时间甩到用户眼前。
     *
     * <p>适用场景：AI 分析结果推送、任务状态变更、需要引起注意的重要通知。
     * 图片建议放在 {@code /comic/*.svg} 等可被飞书公网访问的静态资源路径下；
     * 也可以直接传任意 HTTPS 图片地址。
     *
     * @param title      顶部标题（短，如「AI 洞察报告」）
     * @param theme      标题栏配色
     * @param imageUrl   漫画插图 URL
     * @param imageAlt   图片替代文本
     * @param headline   大标题，卡片最醒目的文字
     * @param highlights 要点列表，每条前面会加视觉标记
     * @param fields     正文字段，按 [名称, 值] 展示
     * @param body       补充正文（Markdown）
     * @param linkText   底部按钮文案
     * @param linkUrl    底部按钮跳转地址
     */
    public record ComicCard(String title, String theme, String imageUrl, String imageAlt,
                            String headline, List<String> highlights, List<List<String>> fields,
                            String body, String linkText, String linkUrl) {

        /**
         * 只有标题、插图、大标题和按钮的最简漫画卡片。
         */
        public static ComicCard visual(String title, String theme, String imageUrl, String headline,
                                       String linkText, String linkUrl) {
            return new ComicCard(title, theme, imageUrl, headline, headline, List.of(), List.of(),
                    null, linkText, linkUrl);
        }

        Map<String, Object> toPayload() {
            List<Map<String, Object>> elements = new ArrayList<>();
            if (imageUrl != null && !imageUrl.isBlank()) {
                // 使用 lark_md 图片语法引用 URL，避免必须先上传图片到飞书获取 img_key。
                // 若后续需要更复杂的 img 元素能力，可再扩展为 img_key 版本。
                String alt = imageAlt == null || imageAlt.isBlank() ? title : imageAlt;
                elements.add(markdown("![" + alt + "](" + imageUrl + ")"));
            }
            if (headline != null && !headline.isBlank()) {
                elements.add(Map.of("tag", "div", "text", Map.of(
                        "tag", "lark_md",
                        "content", "## " + headline)));
            }
            if (highlights != null && !highlights.isEmpty()) {
                StringBuilder md = new StringBuilder();
                for (String item : highlights) {
                    if (item != null && !item.isBlank()) {
                        md.append("**▍** ").append(item).append("\n");
                    }
                }
                if (!md.isEmpty()) {
                    elements.add(markdown(md.toString().trim()));
                }
            }
            for (List<String> field : fields == null ? List.<List<String>>of() : fields) {
                if (field.size() >= 2 && field.get(1) != null && !field.get(1).isBlank()) {
                    elements.add(markdown("**" + field.get(0) + "**：" + field.get(1)));
                }
            }
            if (body != null && !body.isBlank()) {
                if (!elements.isEmpty()) {
                    elements.add(Map.of("tag", "hr"));
                }
                elements.add(markdown(body));
            }
            if (linkText != null && !linkText.isBlank() && linkUrl != null && !linkUrl.isBlank()) {
                elements.add(Map.of("tag", "action", "actions", List.of(Map.of(
                        "tag", "button",
                        "text", Map.of("tag", "plain_text", "content", linkText),
                        "type", "primary",
                        "url", linkUrl))));
            }
            if (elements.isEmpty()) {
                elements.add(markdown(" "));
            }
            return Map.of(
                    "config", Map.of("wide_screen_mode", true),
                    "header", Map.of(
                            "template", theme == null || theme.isBlank() ? "blue" : theme,
                            "title", Map.of("tag", "plain_text", "content", title)),
                    "elements", elements);
        }

        private static Map<String, Object> markdown(String content) {
            return Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", content));
        }
    }

    /**
     * 漫画插图地址生成器：按场景返回内置 SVG 的公网 URL。
     *
     * <p>SVG 文件放在 {@code wao-server/src/main/resources/static/comic/} 下，
     * Spring Boot 会自动通过 {@code /comic/<scene>.svg} 暴露，生产环境前接站点域名即可。
     */
    public static final class ComicIllustrations {

        private ComicIllustrations() {
        }

        public static String insight(String siteUrl) {
            return asset(siteUrl, "insight.svg");
        }

        public static String success(String siteUrl) {
            return asset(siteUrl, "success.svg");
        }

        public static String warning(String siteUrl) {
            return asset(siteUrl, "warning.svg");
        }

        public static String rocket(String siteUrl) {
            return asset(siteUrl, "rocket.svg");
        }

        private static String asset(String siteUrl, String file) {
            String base = siteUrl == null || siteUrl.isBlank() ? "" : siteUrl.strip().replaceFirst("/+$", "");
            return base + "/comic/" + file;
        }
    }
}
