package cn.wayuwa.wao.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.wayuwa.wao.domain.ImageBlobEntity;
import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.SharedTianrenReadingDto;
import cn.wayuwa.wao.feishu.FeishuOpenApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * 为 WAO 的公开分享地址生成飞书 {@code url.preview.get} 响应。
 *
 * <p>预览严格复用公开分享 DTO，不读取成员、评论或私有会话；回调里的 URL 也只当作同源地址解析，
 * 绝不主动请求，避免把公开飞书回调变成 SSRF 出口。
 */
@Service
public class FeishuLinkPreviewService {

    private static final Logger log = LoggerFactory.getLogger(FeishuLinkPreviewService.class);
    private static final Pattern TASK_PAGE = Pattern.compile("^/s/t/([^/]+)(?:/([^/]+))?/?$");
    private static final Pattern TASK_MARKDOWN =
            Pattern.compile("^/api/share/tasks/([^/]+)(?:/subs/([^/]+))?\\.md$");
    private static final Pattern DOC_PAGE = Pattern.compile("^/s/d/([^/]+)/?$");
    private static final Pattern DOC_MARKDOWN = Pattern.compile("^/api/share/docs/([^/]+)\\.md$");
    private static final Pattern TIANREN_PAGE = Pattern.compile("^/s/tianren/([^/]+)/?$");
    private static final Pattern TIANREN_API = Pattern.compile("^/api/share/tianren/([^/]+)/?$");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[([^]]*)]\\([^)]*\\)");
    private static final Pattern NOTE_IMAGE = Pattern.compile("!\\[[^]]*]\\((/api/images/[A-Za-z0-9_-]+/raw)(?:\\s+[^)]*)?\\)");
    private static final Pattern PUBLIC_IMAGE = Pattern.compile("^/api/images/[A-Za-z0-9_-]+/raw$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{0,500}>");
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile("[`*_~#>|]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern SUMMARY_SECTION = Pattern.compile(
            "(?im)^#{1,6}\\s+[^\\r\\n]*(?:快速总结|卦象总结|一句话总结|今日指引|今日建议|简要总结)[^\\r\\n]*\\r?$\\n?");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final int MAX_SOURCE_CHARS = 4_000;
    private static final long IMAGE_UPLOAD_WAIT_MILLIS = 1_800;

    private final ShareService share;
    private final TianrenReadingService tianren;
    private final SettingService settings;
    private final FeishuOpenApiClient feishu;
    private final ImageService images;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TianrenShareVisualProvider tianrenVisuals;
    private final Map<String, CompletableFuture<String>> imageKeys = new ConcurrentHashMap<>();

    public FeishuLinkPreviewService(ShareService share, TianrenReadingService tianren,
            SettingService settings, FeishuOpenApiClient feishu, ImageService images) {
        this.share = share;
        this.tianren = tianren;
        this.settings = settings;
        this.feishu = feishu;
        this.images = images;
    }

    /**
     * 生成一次链接预览；无法确认是当前 WAO 公开分享地址时返回空对象，让飞书使用默认 URL 展示。
     *
     * @param event 飞书事件体中的 event 节点
     * @param baseUrl 当前请求推导出的 WAO 根地址
     * @return 飞书链接预览协议响应，或空对象表示降级
     */
    public Map<String, Object> preview(com.fasterxml.jackson.databind.JsonNode event, String baseUrl) {
        String requestedUrl = event.path("context").path("url").asText("");
        try {
            URI requested = URI.create(requestedUrl);
            URI base = URI.create(baseUrl);
            if (!sameHttpOrigin(requested, base)) return Map.of();
            String path = requested.getRawPath();
            if (!StringUtils.hasText(path)) return Map.of();

            Matcher task = TASK_PAGE.matcher(path);
            if (!task.matches()) task = TASK_MARKDOWN.matcher(path);
            if (task.matches()) {
                return taskPreview(segment(task.group(1)), optionalSegment(task.group(2)),
                        queryIsTrue(requested, "openOnly"), requestedUrl);
            }

            Matcher doc = DOC_PAGE.matcher(path);
            if (!doc.matches()) doc = DOC_MARKDOWN.matcher(path);
            if (doc.matches()) return docPreview(segment(doc.group(1)), requestedUrl);

            Matcher reading = TIANREN_PAGE.matcher(path);
            if (!reading.matches()) reading = TIANREN_API.matcher(path);
            if (reading.matches()) return tianrenPreview(segment(reading.group(1)), requestedUrl);
            return Map.of();
        } catch (RuntimeException exception) {
            // 分享已删除、凭据撤销、URL 不合法或存储暂不可用时都必须在三秒内安全降级。
            log.warn("飞书链接预览已降级：{}", exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, Object> taskPreview(String taskId, String subId, boolean openOnly, String url) {
        SharedTaskDto task = subId == null ? share.shareTask(taskId, openOnly) : share.shareSub(taskId, subId);
        boolean sub = "sub".equals(task.kind());
        List<String> facts = new ArrayList<>();
        addFact(facts, "状态", task.status());
        addFact(facts, "优先级", task.pr());
        addFact(facts, "截止", task.due());
        addFact(facts, "项目", task.project());
        if (!sub && !task.subs().isEmpty()) {
            long pending = task.subs().stream().filter(step -> !step.done()).count();
            facts.add("子任务：" + pending + " 项待办 / " + task.subs().size() + " 项可见");
        }
        String kind = sub ? "WAO 子任务" : "WAO 任务";
        return response(kind, task.title(), facts, plainText(task.desc(), 220), url, "打开任务", "blue");
    }

    private Map<String, Object> docPreview(String docId, String url) {
        SharedDocDto doc = share.shareDoc(docId);
        String kind = doc.note() ? "WAO 小记" : "WAO 文档";
        String title = StringUtils.hasText(doc.title()) ? doc.title() : (doc.note() ? "未命名小记" : "未命名文档");
        if (doc.note()) {
            String image = firstNoteImage(doc);
            String summary = contentSummary(doc.md(), 240);
            return visualResponse(kind, title, List.of(), summary,
                    image == null ? null : previewImageKey(image), url, "打开原文", "turquoise");
        }
        List<String> facts = new ArrayList<>();
        addFact(facts, "更新于", doc.updated());
        return response(kind, title, facts, plainText(doc.md(), 240), url, "打开原文", "turquoise");
    }

    private static String firstNoteImage(SharedDocDto doc) {
        JsonNode document = doc.content() == null ? null : doc.content().document();
        if (document != null && "doc".equals(document.path("type").asText())) {
            return firstImageNode(document);
        }
        Matcher image = NOTE_IMAGE.matcher(doc.md());
        return image.find() ? image.group(1) : null;
    }

    private static String firstImageNode(JsonNode node) {
        if ("image".equals(node.path("type").asText())) {
            String src = node.path("attrs").path("src").asText("");
            if (PUBLIC_IMAGE.matcher(src).matches()) return src;
        }
        for (JsonNode child : node.path("content")) {
            String src = firstImageNode(child);
            if (src != null) return src;
        }
        return null;
    }

    private String previewImageKey(String path) {
        String imageId = path.substring("/api/images/".length(), path.length() - "/raw".length());
        return uploadImageKey("public:" + imageId, () -> images.raw(imageId));
    }

    private String uploadImageKey(String imageVersion, java.util.function.Supplier<ImageBlobEntity> source) {
        String appId = settings.get(SettingService.CHAT_SYNC_APP_ID, "");
        String appSecret = settings.get(SettingService.CHAT_SYNC_APP_SECRET, "");
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) return null;
        String cacheKey = appId + ":" + imageVersion;
        if (!imageKeys.containsKey(cacheKey) && imageKeys.size() >= 256) {
            imageKeys.entrySet().removeIf(entry -> entry.getValue().isDone());
            if (imageKeys.size() >= 256) return null;
        }
        CompletableFuture<String> upload = imageKeys.computeIfAbsent(cacheKey, ignored ->
                CompletableFuture.supplyAsync(() -> {
                    ImageBlobEntity image = source.get();
                    if (image.getContent() == null || image.getContent().length == 0
                            || image.getContent().length > ImageService.MAX_BYTES
                            || image.getContentType() == null
                            || !image.getContentType().startsWith("image/")) {
                        throw new IllegalArgumentException("分享配图格式无效");
                    }
                    String token = feishu.tenantAccessToken(appId, appSecret);
                    String key = feishu.uploadMessageImage(token, image.getContent(), image.getContentType(),
                            "share-preview.png");
                    if (!StringUtils.hasText(key)) throw new IllegalStateException("飞书未返回图片凭据");
                    return key;
                }));
        try {
            return upload.get(IMAGE_UPLOAD_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            // 回调要求三秒内响应；上传继续完成并缓存，下一次分享可直接复用。
            log.warn("飞书分享配图上传未在预览预算内完成");
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            imageKeys.remove(cacheKey, upload);
            log.warn("飞书分享配图上传失败：{}", exception.getClass().getSimpleName());
            return null;
        }
    }

    private static Map<String, Object> visualResponse(String kind, String title, List<String> facts,
            String summary, String imageKey, String url, String button, String theme) {
        String safeTitle = plainText(title, 100);
        List<Map<String, Object>> elements = new ArrayList<>();
        if (imageKey != null) {
            elements.add(Map.of("tag", "img", "img_key", imageKey,
                    "alt", Map.of("tag", "plain_text", "content", "分享配图")));
        }
        for (String fact : facts) elements.add(textElement(plainText(fact, 160)));
        if (StringUtils.hasText(summary)) elements.add(textElement(summary));
        elements.add(Map.of("tag", "action", "actions", List.of(Map.of(
                "tag", "button", "text", Map.of("tag", "plain_text", "content", button),
                "type", "primary", "url", url))));
        String heading = kind + " · " + (StringUtils.hasText(safeTitle) ? safeTitle : "未命名内容");
        return Map.of("inline", Map.of("i18n_title", Map.of("zh_cn", heading)),
                "card", Map.of("type", "raw", "data", Map.of(
                        "config", Map.of("wide_screen_mode", true),
                        "header", Map.of("template", theme,
                                "title", Map.of("tag", "plain_text", "content", heading)),
                        "elements", elements)));
    }

    private Map<String, Object> tianrenPreview(String token, String url) {
        SharedTianrenReadingDto reading = tianren.shared(token);
        String primary = reading.primary().symbol() + " " + reading.primary().name();
        List<String> facts = new ArrayList<>();
        addFact(facts, "日期", reading.date() == null ? null : reading.date().toString());
        facts.add("本卦：" + primary);
        if (reading.relating() == null) {
            facts.add("变化：无变爻");
        } else {
            facts.add("变化：" + reading.changingLines().size() + " 个变爻 · 之卦 "
                    + reading.relating().symbol() + " " + reading.relating().name());
        }
        String interpretation = reading.interpretation() == null ? "" : reading.interpretation();
        String summary = quickSummary(interpretation);
        Matcher image = NOTE_IMAGE.matcher(interpretation);
        String imageKey = null;
        if (tianrenVisuals != null) {
            try {
                var visual = tianrenVisuals.findSharedVisual(token);
                if (visual != null && StringUtils.hasText(interpretation)
                        && interpretation.equals(visual.interpretation())) {
                    if (StringUtils.hasText(visual.summary())) summary = contentSummary(visual.summary(), 240);
                    if (StringUtils.hasText(visual.imageVersion()) && visual.content() != null) {
                        imageKey = uploadImageKey("tianren:" + token + ":" + visual.imageVersion(), () -> {
                            var blob = new ImageBlobEntity();
                            blob.setContent(visual.content());
                            blob.setContentType(visual.contentType());
                            return blob;
                        });
                    }
                }
            } catch (RuntimeException exception) {
                log.warn("卦象分享配图暂不可用：{}", exception.getClass().getSimpleName());
            }
        }
        // 无领域配图模块的旧存档仍可使用解读中已经公开的站内图片。
        if (tianrenVisuals == null && image.find()) imageKey = previewImageKey(image.group(1));
        return visualResponse("WAO 天人一卦", reading.primary().name(), facts,
                summary, imageKey, url, "查看详情", "purple");
    }

    /** 从已经公开的解读中优先取快速总结小节；旧存档使用正文开头，不再调用模型。 */
    static String quickSummary(String interpretation) {
        if (!StringUtils.hasText(interpretation)) return "";
        Matcher summary = SUMMARY_SECTION.matcher(interpretation);
        if (summary.find()) {
            String body = interpretation.substring(summary.end());
            Matcher next = HEADING.matcher(body);
            String section = next.find() ? body.substring(0, next.start()) : body;
            String text = contentSummary(section, 240);
            if (StringUtils.hasText(text)) return text;
        }
        return contentSummary(interpretation, 240);
    }

    private static String contentSummary(String markdown, int limit) {
        return plainText(MARKDOWN_IMAGE.matcher(markdown == null ? "" : markdown).replaceAll(""), limit);
    }

    private static Map<String, Object> response(String kind, String title, List<String> facts, String summary,
            String url, String button, String theme) {
        String safeTitle = plainText(title, 100);
        if (!StringUtils.hasText(safeTitle)) safeTitle = "未命名内容";
        List<Map<String, Object>> elements = new ArrayList<>();
        for (String fact : facts) {
            String text = plainText(fact, 160);
            if (StringUtils.hasText(text)) elements.add(textElement(text));
        }
        if (StringUtils.hasText(summary)) elements.add(textElement(summary));
        elements.add(Map.of("tag", "action", "actions", List.of(Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", button),
                "type", "primary",
                "url", url))));

        Map<String, Object> cardData = Map.of(
                "config", Map.of("wide_screen_mode", true),
                "header", Map.of(
                        "template", theme,
                        "title", Map.of("tag", "plain_text", "content", kind + " · " + safeTitle)),
                "elements", elements);
        return Map.of(
                "inline", Map.of("i18n_title", Map.of("zh_cn", kind + " · " + safeTitle)),
                "card", Map.of("type", "raw", "data", cardData));
    }

    private static Map<String, Object> textElement(String content) {
        return Map.of("tag", "div", "text", Map.of("tag", "plain_text", "content", content));
    }

    private static void addFact(List<String> facts, String label, String value) {
        if (StringUtils.hasText(value)) facts.add(label + "：" + value.trim());
    }

    private static boolean sameHttpOrigin(URI requested, URI base) {
        if (requested.getUserInfo() != null || base.getUserInfo() != null) return false;
        if (!isHttp(requested.getScheme()) || !isHttp(base.getScheme())) return false;
        return requested.getScheme().equalsIgnoreCase(base.getScheme())
                && requested.getHost() != null && base.getHost() != null
                && requested.getHost().equalsIgnoreCase(base.getHost())
                && effectivePort(requested) == effectivePort(base);
    }

    private static boolean isHttp(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean queryIsTrue(URI uri, String name) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().getOrDefault(name, List.of()).stream()
                .anyMatch("true"::equalsIgnoreCase);
    }

    private static String optionalSegment(String raw) {
        return raw == null ? null : segment(raw);
    }

    private static String segment(String raw) {
        String decoded = UriUtils.decode(raw, StandardCharsets.UTF_8);
        if (!StringUtils.hasText(decoded) || decoded.length() > 256 || decoded.indexOf('/') >= 0
                || decoded.indexOf('\\') >= 0 || decoded.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("非法分享地址段");
        }
        return decoded;
    }

    /** 把用户 Markdown / HTML 收敛成卡片纯文本，并在固定长度内结束，避免改变卡片结构。 */
    static String plainText(String source, int maxLength) {
        if (!StringUtils.hasText(source) || maxLength <= 0) return "";
        String value = source.substring(0, Math.min(source.length(), MAX_SOURCE_CHARS));
        value = MARKDOWN_IMAGE.matcher(value).replaceAll("$1");
        value = MARKDOWN_LINK.matcher(value).replaceAll("$1");
        value = HtmlUtils.htmlUnescape(value);
        value = HTML_TAG.matcher(value).replaceAll(" ");
        value = MARKDOWN_DECORATION.matcher(value).replaceAll("");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        if (value.length() <= maxLength) return value;
        return value.substring(0, Math.max(1, maxLength - 1)).stripTrailing() + "…";
    }
}
