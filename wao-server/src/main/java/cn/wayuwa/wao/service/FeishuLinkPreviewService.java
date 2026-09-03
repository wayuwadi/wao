package cn.wayuwa.wao.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.wayuwa.wao.dto.SharedDocDto;
import cn.wayuwa.wao.dto.SharedTaskDto;
import cn.wayuwa.wao.dto.SharedTianrenReadingDto;
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
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{0,500}>");
    private static final Pattern MARKDOWN_DECORATION = Pattern.compile("[`*_~#>|]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_SOURCE_CHARS = 4_000;

    private final ShareService share;
    private final TianrenReadingService tianren;

    public FeishuLinkPreviewService(ShareService share, TianrenReadingService tianren) {
        this.share = share;
        this.tianren = tianren;
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
        List<String> facts = new ArrayList<>();
        addFact(facts, "更新于", doc.updated());
        return response(kind, title, facts, plainText(doc.md(), 240), url, "打开原文", "turquoise");
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
        return response("WAO 天人一卦", reading.primary().name(), facts,
                plainText(reading.interpretation(), 240), url, "打开卦象", "purple");
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
