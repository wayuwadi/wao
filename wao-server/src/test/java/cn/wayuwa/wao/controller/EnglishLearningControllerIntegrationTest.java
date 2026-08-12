package cn.wayuwa.wao.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.dto.SaveEnglishLearningProfileRequest;
import cn.wayuwa.wao.dto.SaveEnglishLearningSessionRequest;
import cn.wayuwa.wao.service.EnglishLearningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 从 HTTP 到数据库验证成员私有的英语目标、日进度、完成门槛和统计。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql("/db/migration/V59__english_learning_practice.sql")
class EnglishLearningControllerIntegrationTest {
    private static final ZoneId WAO_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private EnglishLearningService service;

    @Test
    void savesDailyPracticeOnlyAsCompletedAfterAllFourSkillsAndConfidence() throws Exception {
        LocalDate today = LocalDate.now(WAO_ZONE);
        mvc.perform(get("/api/english-learning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile").doesNotExist())
                .andExpect(jsonPath("$.data.completedDays").value(0));

        mvc.perform(put("/api/english-learning/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SaveEnglishLearningProfileRequest(
                                "能在跨国协作中主动开口", 25, today))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.goal").value("能在跨国协作中主动开口"));

        String incomplete = """
                {"lessonId":"workplace-01","listeningDone":true,"speakingDone":false,
                "readingDone":true,"writingDone":true,"practiceMinutes":20,"confidence":3,
                "writingText":"My introduction.","reflection":"I tried.","nextFocus":"Speak slowly","completed":true}
                """;
        mvc.perform(put("/api/english-learning/sessions/{date}", today)
                        .contentType(MediaType.APPLICATION_JSON).content(incomplete))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("听说读写")));

        String complete = incomplete.replace("\"speakingDone\":false", "\"speakingDone\":true");
        String response = mvc.perform(put("/api/english-learning/sessions/{date}", today)
                        .contentType(MediaType.APPLICATION_JSON).content(complete))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("data").path("id").asText();

        mvc.perform(get("/api/english-learning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedDays").value(1))
                .andExpect(jsonPath("$.data.currentStreak").value(1))
                .andExpect(jsonPath("$.data.totalMinutes").value(20))
                .andExpect(jsonPath("$.data.session.id").value(id))
                .andExpect(jsonPath("$.data.session.writingText").value("My introduction."));
    }

    @Test
    void isolatesProfileAndSessionsByCurrentMember() throws Exception {
        LocalDate today = LocalDate.now(WAO_ZONE);
        service.saveProfile("another-member", new SaveEnglishLearningProfileRequest("另一个人的目标", 30, today));
        service.saveSession("another-member", today, new SaveEnglishLearningSessionRequest(
                "workplace-02", true, true, true, true, 30, 4, "hello", "done", "continue", true));

        String raw = mvc.perform(get("/api/english-learning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile").doesNotExist())
                .andExpect(jsonPath("$.data.recentSessions.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode body = json.readTree(raw).path("data");
        assertThat(body.path("totalMinutes").asInt()).isZero();
        assertThat(service.overview("another-member", today).completedDays()).isEqualTo(1);
    }
}
