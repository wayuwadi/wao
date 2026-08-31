package cn.wayuwa.wao.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.agent.domain.HotspotEntity;
import cn.wayuwa.wao.agent.hotspot.HotspotSource;
import cn.wayuwa.wao.agent.hotspot.HotspotSourceStatus;
import cn.wayuwa.wao.agent.hotspot.RawHotspot;
import cn.wayuwa.wao.agent.mapper.HotspotMapper;
import cn.wayuwa.wao.agent.mapper.WorkspaceProfileMapper;
import cn.wayuwa.wao.agent.mapper.TopicSuggestionMapper;
import cn.wayuwa.wao.agent.service.HotspotService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 选题中心链路。热点来源全部用替身 —— 测试绝不能真的去请求微博知乎，
 * 那既不稳定也是对别人站点的无谓打扰。真实响应的解析由 HotspotSourceParseTest 覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudioFlowIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private HotspotMapper hotspotMapper;
    @Autowired private WorkspaceProfileMapper profileMapper;
    @Autowired private TopicSuggestionMapper suggestionMapper;

    /** 同一个类里的用例共用一个 H2 库和同一个 acc-demo 工作区，不清理就会互相污染。 */
    @BeforeEach
    void clean() {
        hotspotMapper.delete(Wrappers.emptyWrapper());
        profileMapper.delete(Wrappers.emptyWrapper());
        suggestionMapper.delete(Wrappers.emptyWrapper());
    }

    /** 可控的假来源。 */
    private record FakeSource(String code, String label, List<RawHotspot> items, RuntimeException boom)
            implements HotspotSource {
        @Override
        public List<RawHotspot> fetch() {
            if (boom != null) throw boom;
            return items;
        }
    }

    private HotspotService serviceWith(HotspotSource... sources) {
        return new HotspotService(List.of(sources), hotspotMapper);
    }

    @Test
    void oneBrokenSourceDoesNotStopTheOthers() {
        HotspotService service = serviceWith(
                new FakeSource("A", "来源A", List.of(new RawHotspot("a1", "热点甲", null, null, 100L, 1)), null),
                new FakeSource("B", "来源B", List.of(), new IllegalStateException("对方站点改版了")),
                new FakeSource("C", "来源C", List.of(new RawHotspot("c1", "热点丙", null, null, 50L, 1)), null));

        List<HotspotSourceStatus> statuses = service.refreshAll();

        assertThat(statuses).hasSize(3);
        assertThat(statuses.get(0).fetched()).isEqualTo(1);
        assertThat(statuses.get(0).error()).isNull();
        // 失败的来源要留下可读的原因，而不是静默少一批数据
        assertThat(statuses.get(1).error()).contains("对方站点改版了");
        assertThat(statuses.get(2).fetched()).isEqualTo(1);
        assertThat(service.recent(10)).hasSize(2);
    }

    @Test
    void reIngestingUpdatesRankInsteadOfDuplicating() {
        HotspotService first = serviceWith(
                new FakeSource("A", "来源A", List.of(new RawHotspot("a1", "热点甲", null, null, 100L, 5)), null));
        first.refreshAll();

        HotspotService second = serviceWith(
                new FakeSource("A", "来源A", List.of(new RawHotspot("a1", "热点甲改了标题", null, null, 900L, 1)), null));
        second.refreshAll();

        List<HotspotEntity> all = hotspotMapper.selectList(Wrappers.emptyWrapper());
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getRankNo()).isEqualTo(1);
        assertThat(all.get(0).getHeat()).isEqualTo(900L);
        assertThat(all.get(0).getTitle()).isEqualTo("热点甲改了标题");
        // firstSeenAt 要保留，才能看出这条热点已经挂榜多久
        assertThat(all.get(0).getFirstSeenAt()).isNotNull();
    }

    @Test
    void recentTakesTopNPerSourceThenSortsByRank() {
        HotspotService service = serviceWith(
                new FakeSource("A", "来源A", List.of(
                        new RawHotspot("a1", "A第一", null, null, null, 1),
                        new RawHotspot("a2", "A第二", null, null, null, 2),
                        new RawHotspot("a3", "A第三", null, null, null, 3)), null),
                new FakeSource("B", "来源B", List.of(
                        new RawHotspot("b1", "B第一", null, null, null, 1)), null));
        service.refreshAll();

        List<HotspotEntity> picked = service.recent(2);

        assertThat(picked).hasSize(3);
        assertThat(picked).extracting(HotspotEntity::getRankNo).containsExactly(1, 1, 2);
        assertThat(picked).extracting(HotspotEntity::getTitle).doesNotContain("A第三");
    }

    @Test
    void studioReportsSourcesAndEmptyProfileBeforeAnythingIsConfigured() throws Exception {
        mvc.perform(get("/api/agent/studio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.configured", Matchers.is(false)))
                // 抖音热榜要如实声明不可用，并说明用头条作替代
                .andExpect(jsonPath("$.data.sources[?(@.code=='DOUYIN')].available",
                        Matchers.contains(false)))
                .andExpect(jsonPath("$.data.sources[?(@.code=='WEIBO')].available",
                        Matchers.contains(true)));
    }

    @Test
    void profileRoundTripsAndMarksItselfConfigured() throws Exception {
        mvc.perform(post("/api/agent/studio/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"niche":"户外露营装备测评","audience":"周末露营新手","tone":"实在不夸张","avoid":"绝对化用词",
                                 "contentTopics":"装备实测、路线复盘","lengthStructure":"1200 字，先结论后实测",
                                 "updateFrequency":"每周两篇","primaryPlatforms":"公众号、小红书","imageStyle":"户外实拍，少文字"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured", Matchers.is(true)))
                .andExpect(jsonPath("$.data.niche", Matchers.equalTo("户外露营装备测评")))
                .andExpect(jsonPath("$.data.imageStyle", Matchers.equalTo("户外实拍，少文字")));

        mvc.perform(get("/api/agent/studio"))
                .andExpect(jsonPath("$.data.profile.audience", Matchers.equalTo("周末露营新手")))
                .andExpect(jsonPath("$.data.profile.updateFrequency", Matchers.equalTo("每周两篇")));
    }

    @Test
    void manualTopicPersistsWithoutHotspotOrModel() throws Exception {
        String response = mvc.perform(post("/api/agent/studio/topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"把这次工作流写成系列\",\"platform\":\"WECHAT_MP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason", Matchers.equalTo("手动记录")))
                .andExpect(jsonPath("$.data.hotspotId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).at("/data/id").asText();
        mvc.perform(get("/api/agent/studio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestions[?(@.id=='" + id + "')].topic",
                        Matchers.hasItem("把这次工作流写成系列")));
    }

    @Test
    void recommendWithoutModelSaysSoInsteadOfReturningNothing() throws Exception {
        mvc.perform(post("/api/agent/studio/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"DOUYIN\",\"count\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("AGENT_AI")));
    }

    @Test
    void profileInitializationWithoutModelFailsInsteadOfCreatingDemoPositioning() throws Exception {
        mvc.perform(post("/api/agent/studio/profile/initialize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"needs\":\"面向上班族分享真实使用过的 AI 工作流\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("AGENT_AI")));
    }
}
