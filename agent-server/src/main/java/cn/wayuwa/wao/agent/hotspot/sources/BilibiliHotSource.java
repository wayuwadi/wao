package cn.wayuwa.wao.agent.hotspot.sources;

import cn.wayuwa.wao.agent.hotspot.HttpHotspotSource;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * B 站热门视频。响应形如 {@code {code:0, data:{list:[{bvid, title, tname, stat:{view}}]}}}。
 * 这是唯一一个视频形态的来源，对短视频选题的参考价值高于图文榜单。
 */
@Component
public class BilibiliHotSource extends HttpHotspotSource {

    public BilibiliHotSource(RestClient hotspotRestClient) {
        super(hotspotRestClient);
    }

    @Override
    public String code() {
        return "BILIBILI";
    }

    @Override
    public String label() {
        return "B 站热门";
    }

    @Override
    protected String url() {
        return "https://api.bilibili.com/x/web-interface/popular?ps=" + LIMIT + "&pn=1";
    }

    @Override
    protected List<RawHotspot> parse(JsonNode json) {
        if (json.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("B 站接口返回 code=" + json.path("code").asInt()
                    + " " + json.path("message").asText(""));
        }
        List<RawHotspot> list = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : json.path("data").path("list")) {
            String title = item.path("title").asText("").trim();
            if (title.isEmpty()) continue;
            String bvid = item.path("bvid").asText(title);
            Long view = item.path("stat").hasNonNull("view") ? item.path("stat").path("view").asLong() : null;
            // 分区名当摘要，便于推荐时判断领域是否匹配。
            list.add(new RawHotspot(bvid, title, blankToNull(item.path("tname").asText(null)),
                    "https://www.bilibili.com/video/" + bvid, view, rank++));
            if (list.size() >= LIMIT) break;
        }
        return list;
    }
}
