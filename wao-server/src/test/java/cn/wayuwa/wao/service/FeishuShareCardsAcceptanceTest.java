package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 显式启用的既有数据库验收；只读取内容，上传飞书图片，不向会话发送消息。 */
@EnabledIfSystemProperty(named = "feishu.share.eval", matches = "true")
@SpringBootTest(properties = {"wao.ai.local-notes.enabled=false", "wao.ai.discoveries.enabled=false",
        "wao.ai.discoveries.cleanup-enabled=false", "wao.ai.read-only.cleanup-enabled=false",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "wao.vault.encryption-key=${WAO_VAULT_KEY}"})
@ActiveProfiles("local-cloud")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class FeishuShareCardsAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShareService share;
    @Autowired SettingService settings;

    @Test
    void savedNotePublicProjectionAndCallbackKeepTitleTextAndDetailsLink() throws Exception {
        var ids = jdbc.queryForList("SELECT id FROM doc WHERE kb_id='kb-notes' "
                + "AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1", String.class);
        org.junit.jupiter.api.Assumptions.assumeFalse(ids.isEmpty(), "既有数据库没有小记");
        String id = ids.get(0);
        var projection = share.shareDoc(id);
        JsonNode page = json.readTree(mvc.perform(get("/api/share/docs/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(page.path("data").path("md").asText().equals(projection.md())).isTrue();
        JsonNode card = preview("http://localhost/s/d/" + id);
        assertThat(card.path("inline").path("i18n_title").path("zh_cn").asText()).startsWith("WAO 小记 · ");
        JsonNode elements = card.path("card").path("data").path("elements");
        assertThat(elements.get(elements.size() - 1).path("actions").get(0).path("url").asText())
                .isEqualTo("http://localhost/s/d/" + id);
        System.out.println("小记真实数据链路通过：公开正文与飞书回调卡片、原链接跳转；未向会话发送消息");
    }

    @Test
    void savedNoteThroughPublicPageAndVerifiedCallbackUsesRealFeishuImage() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.springframework.util.StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_ID, ""))
                && org.springframework.util.StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")),
                "既有数据库未配置飞书应用凭据，真实图片上传未验证");
        var ids = jdbc.queryForList("SELECT id FROM doc WHERE kb_id='kb-notes' "
                + "AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 100", String.class);
        String id = ids.stream().filter(candidate -> share.shareDoc(candidate).md().contains("/api/images/"))
                .findFirst().orElseThrow(() -> new IllegalStateException("既有数据库无可验收的有图小记"));
        var projection = share.shareDoc(id);
        JsonNode page = json.readTree(mvc.perform(get("/api/share/docs/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(page.path("data").path("md").asText().equals(projection.md())).isTrue();

        // 首次上传可能超出回调预算；第二次只能复用前一次已完成的图片，不能主动发送消息。
        preview("http://localhost/s/d/" + id);
        JsonNode card = preview("http://localhost/api/share/docs/" + id + ".md");
        assertThat(card.path("card").path("data").path("elements").get(0).path("img_key").asText())
                .startsWith("img_");
        assertThat(card.path("inline").path("i18n_title").path("zh_cn").asText()).startsWith("WAO 小记 · ");
        assertThat(card.toString()).doesNotContain("![", "/api/images/", "更新于：");
        System.out.println("小记验收通过：真实公开投影、校验回调、真实飞书图片上传及 Markdown 链接卡片；未发送会话消息");
    }

    @Test
    void existingSharedReadingHasPublicFactsAndDetailsLink() throws Exception {
        var tokens = jdbc.queryForList("SELECT share_token FROM tianren_daily_reading "
                + "WHERE share_token IS NOT NULL ORDER BY reading_date DESC LIMIT 1", String.class);
        org.junit.jupiter.api.Assumptions.assumeFalse(tokens.isEmpty(), "既有数据库没有已分享卦象，真实卦象链路未验证");
        JsonNode card = preview("http://localhost/s/tianren/" + tokens.get(0));
        assertThat(card.path("inline").path("i18n_title").path("zh_cn").asText()).startsWith("WAO 天人一卦 · ");
        assertThat(card.toString()).contains("本卦：", "查看详情").doesNotContain("memberId", "conversationId");
        System.out.println("卦象验收通过：既有公开凭据、真实卦象、详情跳转；私有配图生产者尚未交付");
    }

    private JsonNode preview(String url) throws Exception {
        String body = json.writeValueAsString(Map.of("header", Map.of("event_type", "url.preview.get",
                "token", settings.get(SettingService.QUICK_COLLECT_VERIFY_TOKEN, "")),
                "event", Map.of("context", Map.of("url", url))));
        return json.readTree(mvc.perform(post("/api/feishu/events").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
