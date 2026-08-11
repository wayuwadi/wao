package cn.wayuwa.wao.agent.hotspot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.agent.hotspot.sources.BaiduHotSource;
import cn.wayuwa.wao.agent.hotspot.sources.BilibiliHotSource;
import cn.wayuwa.wao.agent.hotspot.sources.DouyinHotSource;
import cn.wayuwa.wao.agent.hotspot.sources.ToutiaoHotSource;
import cn.wayuwa.wao.agent.hotspot.sources.WeiboHotSource;
import cn.wayuwa.wao.agent.hotspot.sources.ZhihuHotSource;
import cn.wayuwa.wao.agent.platform.douyin.DouyinProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 各热点来源的解析。响应样本取自 2026-07-30 各站点的真实返回，字段名和层级都按当时实际结构。
 * 站点改版时这些用例会先失败，而不是等到线上悄悄少了一批热点。
 */
class HotspotSourceParseTest {
    /** 知乎的 Web 接口要登录 Cookie，只有移动端 UA 走 api.zhihu.com 才免登录，UA 变了就会 401。 */
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Mobile/15E148 Safari/604.1";

    private record Fixture(MockRestServiceServer server, RestClient.Builder builder) {
    }

    private Fixture stub(String url, String body, String... requiredHeaders) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var expect = server.expect(requestTo(url));
        for (int i = 0; i + 1 < requiredHeaders.length; i += 2) {
            expect = expect.andExpect(header(requiredHeaders[i], requiredHeaders[i + 1]));
        }
        expect.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        return new Fixture(server, builder);
    }

    @Test
    void weiboUsesRealtimeListAndRequiresReferer() {
        Fixture f = stub("https://weibo.com/ajax/side/hotSearch", """
                {"ok":1,"data":{"realtime":[
                  {"word":"天价退票费全额退了","num":1282704,"label_name":"热"},
                  {"word":"30岁小伙负债170万卖饺子翻身","num":996202,"label_name":""},
                  {"word":"","num":1}
                ]}}
                """, "Referer", "https://weibo.com/");

        List<RawHotspot> list = new WeiboHotSource(f.builder().build()).fetch();

        f.server().verify();
        // 空标题必须被丢掉，否则会落一条没内容的热点
        assertThat(list).hasSize(2);
        assertThat(list.get(0).title()).isEqualTo("天价退票费全额退了");
        assertThat(list.get(0).heat()).isEqualTo(1282704L);
        assertThat(list.get(0).rank()).isEqualTo(1);
        assertThat(list.get(0).summary()).isEqualTo("热");
        assertThat(list.get(0).url()).contains("s.weibo.com");
        assertThat(list.get(1).rank()).isEqualTo(2);
        assertThat(list.get(1).summary()).isNull();
    }

    @Test
    void baiduReadsPcBoardWithHotScoreAndDesc() {
        Fixture f = stub("https://top.baidu.com/api/board?platform=pc&tab=realtime", """
                {"data":{"cards":[{"content":[
                  {"word":"中央考核巡查组严肃批评","hotScore":"7808181","desc":"近日检查","rawUrl":"https://www.baidu.com/s?wd=x"},
                  {"word":"50℃热穹顶东移","hotScore":"7711964","desc":"","rawUrl":"https://www.baidu.com/s?wd=y"}
                ]}]}}
                """);

        List<RawHotspot> list = new BaiduHotSource(f.builder().build()).fetch();

        f.server().verify();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).heat()).isEqualTo(7808181L);
        assertThat(list.get(0).summary()).isEqualTo("近日检查");
        assertThat(list.get(1).summary()).isNull();
    }

    @Test
    void zhihuParsesTargetTitleAndChineseHeatText() {
        Fixture f = stub("https://api.zhihu.com/topstory/hot-lists/total?limit=50", """
                {"data":[
                  {"type":"hot_list_feed","detail_text":"1395 万热度",
                   "target":{"id":"2065707692950123437","title":"AI 公司收购旧书训练模型","excerpt":"摘要文本"}},
                  {"type":"hot_list_feed","detail_text":"434 万热度",
                   "target":{"id":"2065804781247887148","title":"美团账户遭盗刷"}}
                ]}
                """, "User-Agent", MOBILE_UA);

        List<RawHotspot> list = new ZhihuHotSource(f.builder().build()).fetch();

        f.server().verify();
        assertThat(list).hasSize(2);
        // 「1395 万热度」要还原成 13950000，否则跨条目热度没法比
        assertThat(list.get(0).heat()).isEqualTo(13_950_000L);
        assertThat(list.get(0).url()).endsWith("/question/2065707692950123437");
        assertThat(list.get(1).heat()).isEqualTo(4_340_000L);
    }

    @Test
    void bilibiliUsesViewCountAndPartitionName() {
        Fixture f = stub("https://api.bilibili.com/x/web-interface/popular?ps=50&pn=1", """
                {"code":0,"message":"OK","data":{"list":[
                  {"bvid":"BV1rW326hEKe","title":"抵抗者2026实机演示","tname":"单机游戏","stat":{"view":1293120}}
                ]}}
                """);

        List<RawHotspot> list = new BilibiliHotSource(f.builder().build()).fetch();

        f.server().verify();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).externalId()).isEqualTo("BV1rW326hEKe");
        assertThat(list.get(0).heat()).isEqualTo(1_293_120L);
        assertThat(list.get(0).summary()).isEqualTo("单机游戏");
    }

    @Test
    void bilibiliSurfacesNonZeroBusinessCode() {
        Fixture f = stub("https://api.bilibili.com/x/web-interface/popular?ps=50&pn=1",
                "{\"code\":-412,\"message\":\"请求被拦截\"}");

        assertThatThrownBy(() -> new BilibiliHotSource(f.builder().build()).fetch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-412");
    }

    @Test
    void toutiaoReadsPascalCaseFields() {
        Fixture f = stub("https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc", """
                {"data":[{"ClusterId":7668151999556259347,"Title":"创业板指已抹平今年全部涨幅","HotValue":"10659314"}]}
                """);

        List<RawHotspot> list = new ToutiaoHotSource(f.builder().build()).fetch();

        f.server().verify();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).externalId()).isEqualTo("7668151999556259347");
        assertThat(list.get(0).heat()).isEqualTo(10_659_314L);
    }

    @Test
    void douyinHotspotDeclaresItselfUnavailableInsteadOfFakingData() {
        DouyinHotSource source = new DouyinHotSource(new DouyinProperties("", "", "", "", null));

        assertThat(source.available()).isFalse();
        assertThat(source.note()).contains("头条热榜");
        assertThatThrownBy(source::fetch).isInstanceOf(UnsupportedOperationException.class);
    }
}
