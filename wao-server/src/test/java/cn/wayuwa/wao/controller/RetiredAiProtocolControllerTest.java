package cn.wayuwa.wao.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.service.AiAccountService;
import cn.wayuwa.wao.service.AiAssistantService;
import cn.wayuwa.wao.service.AiEvolutionService;
import cn.wayuwa.wao.service.AiRunService;
import cn.wayuwa.wao.service.AiRunnerLifecycleService;
import cn.wayuwa.wao.service.AiRunnerWakeService;
import cn.wayuwa.wao.service.AiSkillCapabilityService;
import cn.wayuwa.wao.service.AiTaskAutoRunService;
import cn.wayuwa.wao.service.SkillMarketService;
import cn.wayuwa.wao.service.TaskReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** AI Run 与 Runner 下线后，旧协议不能领取工作、提交结果或读取凭据。 */
@WebMvcTest(controllers = {AiRunController.class, RetiredRunnerController.class}, excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE, classes = cn.wayuwa.wao.auth.FeishuBotAdminInterceptor.class))
@Import(GlobalExceptionHandler.class)
class RetiredAiProtocolControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private AiRunService service;
    @MockBean private AiAccountService accountService;
    @MockBean private AiAssistantService assistant;
    @MockBean private AiRunnerWakeService wakeService;
    @MockBean private AiRunnerLifecycleService lifecycleService;
    @MockBean private CurrentMember currentMember;
    @MockBean private AiEvolutionService evolutionService;
    @MockBean private AiTaskAutoRunService taskAutoRunService;
    @MockBean private AiSkillCapabilityService skillCapabilities;
    @MockBean private SkillMarketService skillMarket;
    @MockBean private TaskReviewService taskReviews;

    @Test
    void retiredReviewableTasksAreNotRegistered() throws Exception {
        mockMvc.perform(get("/api/ai/reviewable-tasks"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(taskReviews, currentMember);
    }

    @Test
    void retiredRunnerCannotClaimWorkOrReadAccounts() throws Exception {
        mockMvc.perform(post("/api/ai/runner/assistant-v2/claim")
                        .header("X-WAO-Runner-Token", "retired-test-token"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(41000));
        mockMvc.perform(get("/api/ai/runner/accounts"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(41000))
                .andExpect(jsonPath("$.data").doesNotExist());
        verifyNoInteractions(assistant, accountService, service);
    }

    @Test
    void retiredRunnerCannotCompleteWithOrWithoutAttemptToken() throws Exception {
        for (String body : java.util.List.of(
                "{\"claimToken\":\"test-attempt\",\"exitCode\":0,\"response\":\"完成\"}",
                "{\"exitCode\":0,\"response\":\"旧 Runner 结果\"}")) {
            mockMvc.perform(post("/api/ai/runner/assistant-v2/ac1/complete")
                            .header("X-WAO-Runner-Token", "retired-test-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.code").value(41000));
        }
        verifyNoInteractions(assistant, service);
    }
}
