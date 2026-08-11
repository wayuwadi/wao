package cn.wayuwa.wao.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.platform.PlatformType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TopicRecommenderTest {

    private static String reply(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content.replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"}}]}";
    }

    private static HotspotEntity hotspot(String id, String title) {
        HotspotEntity entity = new HotspotEntity();
        entity.setId(id);
        entity.setSource("WEIBO");
        entity.setTitle(title);
        entity.setRankNo(1);
        return entity;
    }

    private TopicRecommender recommender(String body, MockRestServiceServer[] out) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ai.test/v1/chat/completions"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        out[0] = server;
        return new TopicRecommender(new ChatClient(
                new AiProperties("deepseek", "https://ai.test", "k", "m", 5000), builder.build()));
    }

    @Test
    void mapsIndexBackToTheOriginalHotspot() {
        List<HotspotEntity> hotspots = List.of(hotspot("h1", "露营装备涨价"), hotspot("h2", "某明星塌房"));
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        TopicRecommender rec = recommender(reply("""
                [{"index":0,"topic":"露营装备涨价后怎么买不吃亏","angle":"对比三家","reason":"贴合装备测评","fitScore":88}]
                """), out);

        var results = rec.recommend(hotspots, PlatformType.DOUYIN, null, 5);

        out[0].verify();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).hotspot().getId()).isEqualTo("h1");
        assertThat(results.get(0).topic()).isEqualTo("露营装备涨价后怎么买不吃亏");
        assertThat(results.get(0).fitScore()).isEqualTo(88);
    }

    /** 模型偶尔会编出不存在的编号，必须丢弃而不是错配到别的热点上。 */
    @Test
    void discardsOutOfRangeIndexInsteadOfMisattributing() {
        List<HotspotEntity> hotspots = List.of(hotspot("h1", "露营装备涨价"));
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        TopicRecommender rec = recommender(reply("""
                [{"index":7,"topic":"编造的选题","fitScore":90},
                 {"index":0,"topic":"真实的选题","fitScore":70}]
                """), out);

        var results = rec.recommend(hotspots, PlatformType.DOUYIN, null, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).topic()).isEqualTo("真实的选题");
    }

    @Test
    void emptyArrayMeansNothingFitsAndIsRespected() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        TopicRecommender rec = recommender(reply("[]"), out);

        assertThat(rec.recommend(List.of(hotspot("h1", "无关热点")), PlatformType.DOUYIN, null, 5)).isEmpty();
    }

    @Test
    void noHotspotsMeansNoModelCallAtAll() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TopicRecommender rec = new TopicRecommender(new ChatClient(
                new AiProperties("d", "https://ai.test", "k", "m", 5000), builder.build()));

        assertThat(rec.recommend(List.of(), PlatformType.DOUYIN, null, 5)).isEmpty();
        server.verify();
    }

    @Test
    void fitScoreIsClampedIntoRange() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        TopicRecommender rec = recommender(reply("""
                [{"index":0,"topic":"选题","fitScore":9000}]
                """), out);

        assertThat(rec.recommend(List.of(hotspot("h1", "热点")), PlatformType.DOUYIN, null, 5)
                .get(0).fitScore()).isEqualTo(100);
    }
}
