package cn.wayuwa.wao.agent.hotspot.sources;

import cn.wayuwa.wao.agent.hotspot.HttpHotspotSource;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 知乎热榜。走移动端 api.zhihu.com，Web 端 {@code /api/v3/...} 需要登录 Cookie 会返回 401。
 * 响应形如 {@code {data:[{target:{id,title}, detail_text:"1395 万热度"}]}}。
 */
@Component
public class ZhihuHotSource extends HttpHotspotSource {

    public ZhihuHotSource(RestClient hotspotRestClient) {
        super(hotspotRestClient);
    }

    @Override
    public String code() {
        return "ZHIHU";
    }

    @Override
    public String label() {
        return "知乎热榜";
    }

    @Override
    protected String url() {
        return "https://api.zhihu.com/topstory/hot-lists/total?limit=" + LIMIT;
    }

    @Override
    protected Map<String, String> headers() {
        return Map.of("User-Agent", MOBILE_UA);
    }

    @Override
    protected List<RawHotspot> parse(JsonNode json) {
        List<RawHotspot> list = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : json.path("data")) {
            JsonNode target = item.path("target");
            String title = target.path("title").asText("").trim();
            if (title.isEmpty()) continue;
            String id = target.path("id").asText(title);
            list.add(new RawHotspot(id, title, blankToNull(target.path("excerpt").asText(null)),
                    "https://www.zhihu.com/question/" + id,
                    parseChineseHeat(item.path("detail_text").asText(null)), rank++));
            if (list.size() >= LIMIT) break;
        }
        return list;
    }
}
