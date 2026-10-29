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
    @Autowired TianrenVisualService visuals;

    @Test
    void legacyDocumentIdsNeverPublishSavedTextOrPreviewCards() throws Exception {
        var ids = jdbc.queryForList("SELECT id FROM doc WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT 10", String.class);
        org.junit.jupiter.api.Assumptions.assumeFalse(ids.isEmpty(), "既有数据库没有文档或小记");
        for (String id : ids) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> share.shareDoc(id))
                    .isInstanceOf(cn.wayuwa.wao.common.NotFoundException.class);
            mvc.perform(get("/api/share/docs/" + id)).andExpect(status().isNotFound());
            mvc.perform(get("/api/share/docs/" + id + ".md")).andExpect(status().isNotFound());
            assertThat(preview("http://localhost/s/d/" + id).isEmpty()).isTrue();
            assertThat(preview("http://localhost/api/share/docs/" + id + ".md").isEmpty()).isTrue();
        }
        System.out.println("旧文档 ID 不再返回正文或预览卡片；未创建分享、上传图片或发送消息");
    }

    @Test
    void existingSharedReadingHasPublicFactsAndDetailsLink() throws Exception {
        var tokens = jdbc.queryForList("SELECT share_token FROM tianren_daily_reading "
                + "WHERE share_token IS NOT NULL ORDER BY reading_date DESC LIMIT 1", String.class);
        org.junit.jupiter.api.Assumptions.assumeFalse(tokens.isEmpty(), "既有数据库没有已分享卦象，真实卦象链路未验证");
        JsonNode card = preview("http://localhost/s/tianren/" + tokens.get(0));
        assertThat(card.path("inline").path("i18n_title").path("zh_cn").asText()).startsWith("WAO 天人一卦 · ");
        assertThat(card.toString()).contains("本卦：", "查看详情").doesNotContain("memberId", "conversationId");
        System.out.println("卦象验收通过：既有公开凭据、真实卦象、详情跳转");
    }

    @Test
    void existingSharedVisualUsesSavedSummaryAndRealFeishuImage() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.springframework.util.StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_ID, ""))
                && org.springframework.util.StringUtils.hasText(settings.get(SettingService.CHAT_SYNC_APP_SECRET, "")),
                "既有数据库未配置飞书应用凭据，真实卦象图片上传未验证");
        var tokens = jdbc.queryForList("SELECT share_token FROM tianren_daily_reading "
                + "WHERE share_token IS NOT NULL ORDER BY reading_date DESC", String.class);
        String token = tokens.stream().filter(candidate -> {
            var visual = visuals.findSharedVisual(candidate);
            return visual != null && visual.content() != null;
        }).findFirst().orElseThrow(() -> new IllegalStateException("没有与当前解读对应的已分享成功配图"));
        var visual = visuals.findSharedVisual(token);
        preview("http://localhost/s/tianren/" + token);
        JsonNode card = preview("http://localhost/s/tianren/" + token);
        assertThat(card.path("card").path("data").path("elements").get(0).path("img_key").asText())
                .startsWith("img_");
        assertThat(card.toString()).contains("变：", "不变：", "查看详情")
                .doesNotContain("memberId", "conversationId", "imagePrompt", "scenePrompt");
        assertThat(visual.summary()).isNotBlank();
        System.out.println("卦象图文验收通过：既有生成结果、当前正文版本、保存摘要、真实飞书图片上传和详情链接；未发送会话消息");
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
