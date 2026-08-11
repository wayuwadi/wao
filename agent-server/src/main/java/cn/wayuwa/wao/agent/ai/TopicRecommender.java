package cn.wayuwa.wao.agent.ai;

import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.domain.WorkspaceProfileEntity;
import cn.wayuwa.wao.agent.platform.PlatformType;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 结合账号画像，从热榜里挑出能做的选题并给出切入角度。 */
@Component
public class TopicRecommender {
    private static final Logger log = LoggerFactory.getLogger(TopicRecommender.class);

    private final ChatClient chat;

    public TopicRecommender(ChatClient chat) {
        this.chat = chat;
    }

    public boolean available() {
        return chat.available();
    }

    /**
     * 挑选题。
     *
     * @param hotspots 候选热点，顺序即提示词里的编号顺序
     * @param wanted   期望条数，模型可以少给（没有合适的就该少给）
     */
    public List<Recommendation> recommend(List<HotspotEntity> hotspots, PlatformType platform,
                                          WorkspaceProfileEntity profile, int wanted) {
        if (hotspots.isEmpty()) return List.of();
        StringBuilder listing = new StringBuilder("当前热榜：\n");
        for (int i = 0; i < hotspots.size(); i++) {
            HotspotEntity item = hotspots.get(i);
            listing.append(i).append(". [").append(item.getSource()).append("] ").append(item.getTitle());
            if (item.getSummary() != null && !item.getSummary().isBlank()) {
                listing.append(" —— ").append(truncate(item.getSummary(), 60));
            }
            listing.append('\n');
        }

        String raw = chat.complete(PromptLibrary.recommend(platform, profile, wanted), listing.toString(), 0.7);
        JsonNode array = chat.readJson(raw, true);
        if (!array.isArray()) return List.of();

        List<Recommendation> results = new ArrayList<>();
        for (JsonNode item : array) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= hotspots.size()) {
                // 模型偶尔会编造不存在的编号，丢弃而不是硬凑一条。
                log.debug("忽略越界的热点编号 index={} size={}", index, hotspots.size());
                continue;
            }
            String topic = item.path("topic").asText("").trim();
            if (topic.isBlank()) continue;
            results.add(new Recommendation(hotspots.get(index),
                    truncate(topic, 200),
                    truncate(item.path("angle").asText("").trim(), 1000),
                    truncate(item.path("reason").asText("").trim(), 1000),
                    clamp(item.path("fitScore").asInt(0))));
            if (results.size() >= wanted) break;
        }
        return results;
    }

    private int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    /**
     * 一条选题建议。
     *
     * @param fitScore 与账号定位的契合度 0-100，由模型给出，仅用于排序参考
     */
    public record Recommendation(HotspotEntity hotspot, String topic, String angle,
                                 String reason, int fitScore) {
    }
}
