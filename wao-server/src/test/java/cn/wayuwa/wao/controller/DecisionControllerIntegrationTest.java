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
        mvc.perform(post("/api/ai/runner/claim")).andExpect(status().isGone());
        mvc.perform(get("/api/share/ai-runs/old.md")).andExpect(status().isNotFound());
    }
    @Test void runnerCannotPairClaimReadCredentialsOrUpload() throws Exception {
        mvc.perform(post("/api/ai/runner/pair")).andExpect(status().isGone());
        mvc.perform(post("/api/ai/runner/heartbeat")).andExpect(status().isGone());
        mvc.perform(get("/api/ai/runner/accounts")).andExpect(status().isGone());
        mvc.perform(post("/api/ai/runner/assistant-v2/claim")).andExpect(status().isGone());
        mvc.perform(post("/api/ai/runner/assistant-v2/old/image")).andExpect(status().isGone());
        // 浏览器配图恢复，但不向匿名调用开放；Runner 上传仍维持上面的 410。
        mvc.perform(post("/api/assistant/article-images").contentType("application/json")
                .content("{\"articleText\":\"合成正文\",\"visualPrompt\":\"简洁插画\"}"))
                .andExpect(status().isUnauthorized());
    }
    @Test void retiredWeKnoraCannotSyncOrReadConfiguration() throws Exception {
        mvc.perform(get("/api/weknora-sync")).andExpect(status().isNotFound());
        mvc.perform(post("/api/weknora-sync/run")).andExpect(status().isNotFound());
        mvc.perform(put("/api/weknora-sync")).andExpect(status().isNotFound());
        mvc.perform(get("/api/weknora-sync/documents?ids=old")).andExpect(status().isNotFound());
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
