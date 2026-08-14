package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.domain.TianrenDailyReadingEntity;
import cn.wayuwa.wao.domain.AiAssistantConversationEntity;
import cn.wayuwa.wao.domain.AiAssistantMessageEntity;
import cn.wayuwa.wao.mapper.AiAssistantConversationMapper;
import cn.wayuwa.wao.mapper.AiAssistantMessageMapper;
import cn.wayuwa.wao.mapper.TianrenDailyReadingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 从成员接口、真实 Mapper 到免登录接口验证一份卦象的完整分享链路。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tianrensharetest;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
@Transactional
@Sql({"/tianren-share-schema.sql", "/db/migration/V54__tianren_daily_reading.sql",
        "/db/migration/V62__tianren_reading_share.sql"})
class TianrenShareControllerIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TianrenDailyReadingMapper readings;
    @Autowired private AiAssistantConversationMapper conversations;
    @Autowired private AiAssistantMessageMapper messages;

    @BeforeEach
    void seedReading() {
        LocalDateTime interpretedAt = LocalDateTime.of(2026, 8, 13, 10, 0);
        AiAssistantConversationEntity conversation = new AiAssistantConversationEntity();
        conversation.setId("cv-share-test");
        conversation.setMemberId("wg");
        conversation.setTitle("今日天人一卦");
        conversation.setCreatedAt(interpretedAt.minusMinutes(2));
        conversation.setUpdatedAt(interpretedAt);
        conversations.insert(conversation);

        AiAssistantMessageEntity focus = message("am-focus", "user", "最近总在赶进度", interpretedAt.minusMinutes(1));
        AiAssistantMessageEntity interpretation = message("am-answer", "assistant",
                "## 四时自照\n\n把今天的任务收敛到一件小事。", interpretedAt);
        messages.insert(focus);
        messages.insert(interpretation);

        TianrenDailyReadingEntity row = new TianrenDailyReadingEntity();
        row.setId("tr-share-test");
        row.setMemberId("wg");
        row.setReadingDate(LocalDate.of(2026, 8, 13));
        row.setLinesJson("[7,7,7,8,8,8]");
        row.setPrimaryNumber(11);
        row.setConversationId(conversation.getId());
        row.setCreatedAt(LocalDateTime.of(2026, 8, 13, 9, 30));
        row.setUpdatedAt(row.getCreatedAt());
        readings.insert(row);
    }

    @Test
    void createsStableTokenThenReadsOnlyThePublicProjection() throws Exception {
        String first = mvc.perform(post("/api/tianren/readings/{id}/share", "tr-share-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(first).path("data").path("token").asText();

        mvc.perform(post("/api/tianren/readings/{id}/share", "tr-share-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value(token));

        String raw = mvc.perform(get("/api/share/tianren/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-08-13"))
                .andExpect(jsonPath("$.data.primary.number").value(11))
                .andExpect(jsonPath("$.data.primary.name").value("泰"))
                .andExpect(jsonPath("$.data.lines.length()").value(6))
                .andExpect(jsonPath("$.data.interpretation").value(
                        "## 四时自照\n\n把今天的任务收敛到一件小事。"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.memberId").doesNotExist())
                .andExpect(jsonPath("$.data.conversationId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        JsonNode shared = json.readTree(raw).path("data");
        assertThat(shared.fieldNames()).toIterable()
                .containsExactlyInAnyOrder(
                        "date", "lines", "changingLines", "primary", "relating", "interpretation", "createdAt");
        assertThat(raw).doesNotContain("最近总在赶进度", "cv-share-test", "am-focus", "memberId");
    }

    @Test
    void invalidTokenReturnsNotFoundWithoutLeakingStorageDetails() throws Exception {
        mvc.perform(get("/api/share/tianren/not-a-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("分享链接无效"));
    }

    private AiAssistantMessageEntity message(String id, String role, String content, LocalDateTime createdAt) {
        AiAssistantMessageEntity row = new AiAssistantMessageEntity();
        row.setId(id);
        row.setConversationId("cv-share-test");
        row.setRole(role);
        row.setContent(content);
        row.setCreatedAt(createdAt);
        return row;
    }
}
