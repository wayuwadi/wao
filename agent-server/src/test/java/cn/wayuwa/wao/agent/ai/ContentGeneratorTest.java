package cn.wayuwa.wao.agent.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import cn.wayuwa.wao.agent.common.BadRequestException;
import cn.wayuwa.wao.agent.domain.WorkspaceProfileEntity;
import cn.wayuwa.wao.agent.platform.PlatformType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ContentGeneratorTest {

    private static AiProperties props(String apiKey) {
        return new AiProperties("deepseek", "https://ai.test", apiKey, "deepseek-chat", 5000);
    }

    /** 把模型回复包成 chat/completions 响应；content 里的引号要转义两层。 */
    private static String reply(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n") + "\"}}]}";
    }

    private ContentGenerator generator(String apiKey, MockRestServiceServer[] out, String body) {
        return generator(apiKey, out, body, new String[0]);
    }

    private ContentGenerator generator(String apiKey, MockRestServiceServer[] out, String body,
                                       String... expectedRequestFragments) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        if (body != null) {
            var expectation = server.expect(requestTo("https://ai.test/v1/chat/completions"))
                    .andExpect(header("Authorization", "Bearer " + apiKey));
            for (String fragment : expectedRequestFragments) {
                expectation.andExpect(content().string(containsString(fragment)));
            }
            expectation.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        }
        if (out != null) out[0] = server;
        return new ContentGenerator(new ChatClient(props(apiKey), builder.build()));
    }

    @Test
    void withoutApiKeyItReportsUnavailableInsteadOfFakingCopy() {
        ContentGenerator gen = generator("", null, null);

        assertThat(gen.available()).isFalse();
        assertThatThrownBy(() -> gen.generate("露营", PlatformType.DOUYIN, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("AGENT_AI_BASE_URL");
    }

    @Test
    void baseUrlIsNormalisedToEndWithV1() {
        assertThat(props("k").base()).isEqualTo("https://ai.test/v1");
        assertThat(new AiProperties("d", "https://ai.test/v1/", "k", "m", null).base())
                .isEqualTo("https://ai.test/v1");
    }

    @Test
    void douyinProducesScriptBlocksSeparateFromCaption() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        ContentGenerator gen = generator("key-1", out, reply("""
                {"title":"露营三件套","coverText":"别再乱买了","body":"三件让露营舒适度翻倍的装备",
                 "tags":["#露营","户外好物"],
                 "blocks":[{"label":"钩子","text":"你露营睡不好，不是床垫的锅","seconds":3},
                           {"label":"干货","text":"第一件是……","seconds":20}]}
                """));

        GeneratedContent copy = gen.generate("夏天露营装备", PlatformType.DOUYIN, null);

        out[0].verify();
        assertThat(copy.title()).isEqualTo("露营三件套");
        assertThat(copy.coverText()).isEqualTo("别再乱买了");
        // body 是发布用的短文案，不能被脚本全文覆盖
        assertThat(copy.body()).isEqualTo("三件让露营舒适度翻倍的装备");
        assertThat(copy.blocks()).hasSize(2);
        assertThat(copy.blocks().get(0).label()).isEqualTo("钩子");
        assertThat(copy.blocks().get(0).seconds()).isEqualTo(3);
        assertThat(copy.totalSeconds()).isEqualTo(23);
        assertThat(copy.tags()).containsExactly("露营", "户外好物");
    }

    @Test
    void articleWithoutBodyFallsBackToJoinedBlocks() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        ContentGenerator gen = generator("key-1", out, reply("""
                {"title":"标题","tags":[],
                 "blocks":[{"label":"导语","text":"第一段","seconds":null},
                           {"label":"小标题一","text":"第二段","seconds":null}]}
                """));

        GeneratedContent copy = gen.generate("话题", PlatformType.WECHAT_MP, null);

        out[0].verify();
        // 模型漏给 body 时必须兜底，否则发布时没有正文
        assertThat(copy.body()).isEqualTo("第一段\n\n第二段");
        assertThat(copy.blocks().get(0).seconds()).isNull();
        assertThat(copy.coverText()).isNull();
    }

    @Test
    void toleratesModelWrappingJsonInCodeFence() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        ContentGenerator gen = generator("key-1", out,
                reply("好的：\n```json\n{\"title\":\"标题\",\"body\":\"正文\",\"tags\":[],\"blocks\":[]}\n```"));

        GeneratedContent copy = gen.generate("话题", PlatformType.XIAOHONGSHU, null);

        out[0].verify();
        assertThat(copy.title()).isEqualTo("标题");
        assertThat(copy.blocks()).isEmpty();
    }

    @Test
    void rejectsNonJsonAnswerInsteadOfGuessing() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        ContentGenerator gen = generator("key-1", out, reply("抱歉，我无法完成这个请求。"));

        assertThatThrownBy(() -> gen.generate("话题", PlatformType.WECHAT_MP, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void profileIsFoldedIntoThePrompt() {
        WorkspaceProfileEntity profile = new WorkspaceProfileEntity();
        profile.setNiche("户外露营装备测评");
        profile.setAudience("一线城市周末露营新手");
        profile.setAvoid("绝对化用词");

        String prompt = PromptLibrary.content(PlatformType.DOUYIN, profile);

        assertThat(prompt).contains("户外露营装备测评", "一线城市周末露营新手", "绝对化用词");
        assertThat(PromptLibrary.content(PlatformType.DOUYIN, null)).doesNotContain("账号领域");
    }

    @Test
    void sourceNotesAndGroundedContextAreSentToTheModel() {
        MockRestServiceServer[] out = new MockRestServiceServer[1];
        ContentGenerator gen = generator("key-1", out,
                reply("{\"title\":\"标题\",\"body\":\"正文\",\"tags\":[],\"blocks\":[]}"),
                "我的想法或对话摘录", "朋友说不会使用 Codex",
                "wao_creator_context", "过往成稿只用于表达风格");

        gen.generate("为什么做 WAO", PlatformType.WECHAT_MP, null,
                "朋友说不会使用 Codex",
                "<wao_creator_context>过往成稿只用于表达风格</wao_creator_context>");

        out[0].verify();
    }
}
