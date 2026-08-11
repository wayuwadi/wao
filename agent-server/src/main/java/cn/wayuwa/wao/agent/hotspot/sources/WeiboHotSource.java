package cn.wayuwa.wao.agent.hotspot.sources;

import cn.wayuwa.wao.agent.hotspot.HttpHotspotSource;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 微博热搜。响应形如 {@code {ok:1, data:{realtime:[{word, num, label_name}]}}}，缺 Referer 会返回 403。 */
@Component
public class WeiboHotSource extends HttpHotspotSource {

    public WeiboHotSource(RestClient hotspotRestClient) {
        super(hotspotRestClient);
    }

    @Override
    public String code() {
        return "WEIBO";
    }

    @Override
    public String label() {
        return "微博热搜";
    }

    @Override
    protected String url() {
        return "https://weibo.com/ajax/side/hotSearch";
    }

    @Override
    protected Map<String, String> headers() {
        return Map.of("User-Agent", DESKTOP_UA, "Referer", "https://weibo.com/");
    }

    @Override
    protected List<RawHotspot> parse(JsonNode json) {
        List<RawHotspot> list = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : json.path("data").path("realtime")) {
            String word = item.path("word").asText("").trim();
            if (word.isEmpty()) continue;
            Long heat = item.hasNonNull("num") ? item.path("num").asLong() : null;
            String search = "https://s.weibo.com/weibo?q="
                    + URLEncoder.encode("#" + word + "#", StandardCharsets.UTF_8);
            list.add(new RawHotspot(word, word, blankToNull(item.path("label_name").asText(null)),
                    search, heat, rank++));
            if (list.size() >= LIMIT) break;
        }
        return list;
    }
}
