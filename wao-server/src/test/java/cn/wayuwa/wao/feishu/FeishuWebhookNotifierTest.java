package cn.wayuwa.wao.feishu;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FeishuWebhookNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void comicCardPutsImageAsMarkdownAndKeepsButton() throws Exception {
        FeishuWebhookNotifier.ComicCard card = new FeishuWebhookNotifier.ComicCard(
                "AI 洞察报告", "blue",
                "https://wao.wayuwa.cn/comic/insight.svg", "洞察插图",
                "发现了 7 条想法",
                List.of("想法 1", "想法 2"),
                List.of(List.of("项目", "Wa All in One")),
                "正文",
                "查看详情", "https://wao.wayuwa.cn/projects/p1");

        Map<String, Object> payload = card.toPayload();
        JsonNode root = mapper.valueToTree(payload);

        assertThat(root.path("header").path("title").path("content").asText()).isEqualTo("AI 洞察报告");
        assertThat(root.path("elements").get(0).path("text").path("content").asText())
                .isEqualTo("![洞察插图](https://wao.wayuwa.cn/comic/insight.svg)");
        assertThat(root.path("elements").get(1).path("text").path("content").asText())
                .contains("发现了 7 条想法");

        JsonNode action = root.path("elements").get(root.path("elements").size() - 1);
        assertThat(action.path("tag").asText()).isEqualTo("action");
        assertThat(action.path("actions").get(0).path("url").asText())
                .isEqualTo("https://wao.wayuwa.cn/projects/p1");
    }

    @Test
    void comicCardSkipsEmptyHighlightsAndFields() {
        FeishuWebhookNotifier.ComicCard card = FeishuWebhookNotifier.ComicCard.visual(
                "完成", "green", "https://wao.wayuwa.cn/comic/success.svg",
                "任务已完成", "查看任务", "https://wao.wayuwa.cn/tasks/t1");

        Map<String, Object> payload = card.toPayload();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> elements = (List<Map<String, Object>>) payload.get("elements");
        assertThat(elements).hasSize(3); // image, headline, button
    }

    @Test
    void comicIllustrationsBuildsAbsoluteUrls() {
        assertThat(FeishuWebhookNotifier.ComicIllustrations.insight("https://wao.wayuwa.cn"))
                .isEqualTo("https://wao.wayuwa.cn/comic/insight.svg");
        assertThat(FeishuWebhookNotifier.ComicIllustrations.success("https://wao.wayuwa.cn/"))
                .isEqualTo("https://wao.wayuwa.cn/comic/success.svg");
    }
}
