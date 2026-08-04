package cn.wayuwa.wao.feishu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cn.wayuwa.wao.service.SettingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 飞书群机器人通知：全局一个 webhook，系统里所有需要「说一声」的地方都走它。
 *
 * <p>地址由用户在「设置 → 通知」里填，加密存 {@code system_setting}（见 {@link SettingService}），
 * 因此既不进 Git 也不必改环境变量重启。
 */
@Component
public class FeishuWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookNotifier.class);

    private final SettingService settings;
    private final RestClient client;
    /** 老的部署级 webhook（{@code FEISHU_COMMENT_WEBHOOK_URL}）：界面上还没配时继续用它，线上通知不断档。 */
    private final String legacyUrl;

    public FeishuWebhookNotifier(SettingService settings, RestClient.Builder builder,
            @Value("${feishu.comment-webhook-url:}") String legacyUrl) {
        this.settings = settings;
        this.client = builder.build();
        this.legacyUrl = legacyUrl;
    }

    /** 实际要用的地址：界面上配过就用界面的，否则回落到部署环境变量。 */
    public String resolvedUrl() {
        String configured = settings.get(SettingService.FEISHU_WEBHOOK_URL);
        return configured != null && !configured.isBlank() ? configured : legacyUrl;
    }

    /**
     * 通知是否可用。
     *
     * <p>界面上配过地址时以启用开关为准；只有环境变量兜底时视为启用 —— 那是老部署的既有行为，
     * 不该因为这次多了个开关就悄悄停掉。
     */
    public boolean enabled() {
        String configured = settings.get(SettingService.FEISHU_WEBHOOK_URL);
        if (configured != null && !configured.isBlank()) {
            return settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED);
        }
        return legacyUrl != null && !legacyUrl.isBlank();
    }

    /**
     * 按当前配置发一张卡片通知；没配置或未启用时直接跳过。
     *
     * @param card 卡片内容
     * @return 是否真的发出去了
     */
    public boolean notifyCard(Card card) {
        if (!enabled()) {
            return false;
        }
        try {
            send(resolvedUrl(), card);
            return true;
        } catch (RuntimeException ex) {
            // 通知失败不该影响触发它的业务动作，记一条日志就够了
            log.warn("飞书 webhook 通知发送失败：{}", ex.getMessage());
            return false;
        }
    }

    /**
     * 按当前配置发一张漫画风格卡片通知；没配置或未启用时直接跳过。
     *
     * @param card 漫画卡片内容
     * @return 是否真的发出去了
     */
    public boolean notifyComicCard(ComicCard card) {
        if (!enabled()) {
            return false;
        }
        try {
            sendComic(resolvedUrl(), card);
            return true;
        } catch (RuntimeException ex) {
            log.warn("飞书 webhook 漫画卡片发送失败：{}", ex.getMessage());
            return false;
        }
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
            String base = siteUrl == null || siteUrl.isBlank() ? "" : siteUrl.stripTrailing();
            return base + "/comic/" + file;
        }
    }
}
