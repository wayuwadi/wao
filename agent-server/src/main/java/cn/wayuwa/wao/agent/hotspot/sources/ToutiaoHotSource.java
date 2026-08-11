package cn.wayuwa.wao.agent.hotspot.sources;

import cn.wayuwa.wao.agent.hotspot.HttpHotspotSource;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 今日头条热榜。响应形如 {@code {data:[{ClusterId, Title, HotValue}]}}，字段是大驼峰。
 * 头条与抖音同属字节系，其热榜与抖音站内热点重合度较高，在拿不到抖音开放平台凭据前是最接近的替代。
 */
@Component
public class ToutiaoHotSource extends HttpHotspotSource {

    public ToutiaoHotSource(RestClient hotspotRestClient) {
        super(hotspotRestClient);
    }

    @Override
    public String code() {
        return "TOUTIAO";
    }

    @Override
    public String label() {
        return "头条热榜";
    }

    @Override
    protected String url() {
        return "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc";
    }

    @Override
    protected List<RawHotspot> parse(JsonNode json) {
        List<RawHotspot> list = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : json.path("data")) {
            String title = item.path("Title").asText("").trim();
            if (title.isEmpty()) continue;
            String id = item.path("ClusterId").asText(title);
            Long heat = item.hasNonNull("HotValue") ? parseChineseHeat(item.path("HotValue").asText()) : null;
            list.add(new RawHotspot(id, title, null,
                    "https://www.toutiao.com/trending/" + id + "/", heat, rank++));
            if (list.size() >= LIMIT) break;
        }
        return list;
    }
}
