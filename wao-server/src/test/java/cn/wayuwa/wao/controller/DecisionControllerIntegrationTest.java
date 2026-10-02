package cn.wayuwa.wao.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 决策下线后旧客户端不能继续读写；不删除共享记忆数据。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:decisionretirement;DB_CLOSE_DELAY=-1;MODE=MySQL")
@AutoConfigureMockMvc
class DecisionControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Test void projectRunCannotBeConfiguredLaunchedOrClaimed() throws Exception {
        mvc.perform(get("/api/ai/workflows")).andExpect(status().isNotFound());
        mvc.perform(get("/api/ai/projects/old/config")).andExpect(status().isNotFound());
        mvc.perform(post("/api/ai/runs")).andExpect(status().isNotFound());
        mvc.perform(post("/api/ai/goals")).andExpect(status().isNotFound());
        mvc.perform(post("/api/ai/runner/claim")).andExpect(status().isNotFound());
        mvc.perform(get("/api/share/ai-runs/old.md")).andExpect(status().isNotFound());
    }
    @Test void retiredRoutesCannotReadOrWrite() throws Exception {
        mvc.perform(get("/api/workday-rhythm/dashboard")).andExpect(status().isNotFound());
        mvc.perform(get("/api/workday-rhythm/focus/pending")).andExpect(status().isNotFound());
        mvc.perform(post("/api/workday-rhythm/energy-notifications")).andExpect(status().isNotFound());
        mvc.perform(get("/api/auth/jumpserver")).andExpect(status().isNotFound());
        mvc.perform(get("/api/decisions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/decisions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/decisions/old/revisions")).andExpect(status().isNotFound());
        mvc.perform(post("/api/decisions/old/archive")).andExpect(status().isNotFound());
    }
}
