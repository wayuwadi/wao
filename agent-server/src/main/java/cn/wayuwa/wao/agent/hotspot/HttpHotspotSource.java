package cn.wayuwa.wao.agent.hotspot;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.client.RestClient;

/**
 * 基于公开 JSON 接口的热点来源基类。
 *
 * <p>各站点对 UA / Referer 有不同要求（微博缺 Referer 返回 403，知乎需要移动端 UA），
 * 因此请求头由子类声明。榜单接口本身对外开放，这里不携带任何登录态。
 */
public abstract class HttpHotspotSource implements HotspotSource {
    protected static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";
    protected static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Mobile/15E148 Safari/604.1";
    /** 单个榜单最多取这么多条，再多对选题没有帮助。 */
    protected static final int LIMIT = 50;

    private final RestClient restClient;

    protected HttpHotspotSource(RestClient hotspotRestClient) {
        this.restClient = hotspotRestClient;
    }

    /** 目标接口地址。 */
    protected abstract String url();

    /** 额外请求头，默认只带桌面 UA。 */
    protected Map<String, String> headers() {
        return Map.of("User-Agent", DESKTOP_UA);
    }

    /** 把接口响应解析为热点列表。 */
    protected abstract java.util.List<RawHotspot> parse(JsonNode json);

    @Override
    public java.util.List<RawHotspot> fetch() {
        Map<String, String> all = new LinkedHashMap<>(headers());
        var request = restClient.get().uri(url());
        all.forEach(request::header);
        JsonNode json = request.retrieve().body(JsonNode.class);
        if (json == null) throw new IllegalStateException(label() + " 返回空响应");
        return parse(json);
    }

    /** 榜单里常见「1395 万热度」这类文本，抽出数值并还原量级。 */
    protected static Long parseChineseHeat(String text) {
        if (text == null || text.isBlank()) return null;
        var matcher = java.util.regex.Pattern.compile("([\\d.]+)\\s*(万|亿)?").matcher(text);
        if (!matcher.find()) return null;
        try {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2);
            if ("万".equals(unit)) value *= 10_000;
            else if ("亿".equals(unit)) value *= 100_000_000L;
            return (long) value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 空字符串统一收敛成 null，避免落库出现空串。 */
    protected static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
