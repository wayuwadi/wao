package cn.wayuwa.wao.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.wayuwa.wao.auth.CurrentMember;
import cn.wayuwa.wao.common.GlobalExceptionHandler;
import cn.wayuwa.wao.dto.AiAssistantClaimDto;
import cn.wayuwa.wao.service.AiAccountService;
import cn.wayuwa.wao.service.AiAssistantService;
import cn.wayuwa.wao.service.AiEvolutionService;
import cn.wayuwa.wao.service.AiRunService;
import cn.wayuwa.wao.service.AiTaskAutoRunService;
import cn.wayuwa.wao.service.AiRunnerLifecycleService;
import cn.wayuwa.wao.service.AiRunnerWakeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Runner 的 assistant 领取与完成接口必须完整传递尝试令牌。 */
@WebMvcTest(AiRunController.class)
@Import(GlobalExceptionHandler.class)
class AiRunAssistantProtocolControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private AiRunService service;
    @MockBean private AiAccountService accountService;
    @MockBean private AiAssistantService assistant;
    @MockBean private AiRunnerWakeService wakeService;
    @MockBean private AiRunnerLifecycleService lifecycleService;
    @MockBean private CurrentMember currentMember;
    @MockBean private AiEvolutionService evolutionService;
    @MockBean private AiTaskAutoRunService taskAutoRunService;

    @Test
    void claimReturnsTheAttemptTokenToTheRunner() throws Exception {
        when(assistant.claim("runner-secret")).thenReturn(
                new AiAssistantClaimDto("ac1", "attempt-new", "请总结", 1740, null));

        mockMvc.perform(post("/api/ai/runner/assistant-v2/claim")
                        .header("X-WAO-Runner-Token", "runner-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("ac1"))
                .andExpect(jsonPath("$.data.claimToken").value("attempt-new"))
                .andExpect(jsonPath("$.data.maxRuntimeSeconds").value(1740));
    }

    @Test
    void completeRequiresAndForwardsTheAttemptToken() throws Exception {
        mockMvc.perform(post("/api/ai/runner/assistant-v2/ac1/complete")
                        .header("X-WAO-Runner-Token", "runner-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"claimToken":"attempt-new","exitCode":0,
                                 "response":"完成","error":null}
                                """))
                .andExpect(status().isOk());
        verify(assistant).complete(eq("runner-secret"), eq("ac1"),
                argThat(request -> "attempt-new".equals(request.claimToken())));

        mockMvc.perform(post("/api/ai/runner/assistant-v2/ac1/complete")
                        .header("X-WAO-Runner-Token", "runner-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"exitCode":0,"response":"旧 Runner 结果","error":null}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        verify(assistant, never()).complete(eq("runner-secret"), eq("ac1"),
                argThat(request -> request.claimToken() == null));
    }
}
