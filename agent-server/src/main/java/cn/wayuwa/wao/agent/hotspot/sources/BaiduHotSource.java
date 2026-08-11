package cn.wayuwa.wao.agent.hotspot.sources;

import cn.wayuwa.wao.agent.hotspot.HttpHotspotSource;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 百度热搜。用 pc 版接口，因为 wise 版只返回 word 和 url，没有 hotScore 和 desc。
 * 响应形如 {@code {data:{cards:[{content:[{word, hotScore, desc, rawUrl}]}]}}}。
 */
@Component
public class BaiduHotSource extends HttpHotspotSource {

    public BaiduHotSource(RestClient hotspotRestClient) {
        super(hotspotRestClient);
    }

    @Override
    public String code() {
        return "BAIDU";
    }

    @Override
    public String label() {
        return "百度热搜";
    }

    @Override
    protected String url() {
        return "https://top.baidu.com/api/board?platform=pc&tab=realtime";
    }

    @Override
    protected List<RawHotspot> parse(JsonNode json) {
        List<RawHotspot> list = new ArrayList<>();
        int rank = 1;
        for (JsonNode card : json.path("data").path("cards")) {
            for (JsonNode item : card.path("content")) {
                String word = item.path("word").asText("").trim();
                if (word.isEmpty()) continue;
                Long heat = item.hasNonNull("hotScore") ? parseChineseHeat(item.path("hotScore").asText()) : null;
                list.add(new RawHotspot(word, word, blankToNull(item.path("desc").asText(null)),
                        blankToNull(item.path("rawUrl").asText(null)), heat, rank++));
                if (list.size() >= LIMIT) return list;
            }
        }
        return list;
    }
}
