package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiAssistantRunEntity;
import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.AiAssistantClaimDto;
import cn.wayuwa.wao.dto.AiRunAccountDto;
import cn.wayuwa.wao.dto.AssistantChatRequest;
import cn.wayuwa.wao.dto.CompleteAiAssistantRunRequest;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiAssistantServiceTest {

    private final AiAssistantRunMapper mapper = mock(AiAssistantRunMapper.class);
    private final AiRunnerNodeMapper runnerMapper = mock(AiRunnerNodeMapper.class);
    private final AiRunService aiRunService = mock(AiRunService.class);
    private final AiAccountService accountService = mock(AiAccountService.class);
    private final AiAssistantConversationService conversationService = mock(AiAssistantConversationService.class);
    private final AiAssistantAssetService assetService = mock(AiAssistantAssetService.class);
    private final AiAssistantService service = new AiAssistantService(
            mapper, runnerMapper, aiRunService, accountService, conversationService, assetService);

    @Test
    void statusAcceptsAnyOnlineLocalCodexRunner() {
        AiRunnerNodeEntity windows = runner("Windows Server 2025", "codex");
        AiRunnerNodeEntity linuxWithoutCodex = runner("Linux 6.8", "claude");
        when(runnerMapper.selectList(any())).thenReturn(List.of(windows, linuxWithoutCodex));

        assertThat(service.status().configured()).isTrue();
        assertThat(service.status().message()).contains("本地节点");

        when(runnerMapper.selectList(any())).thenReturn(List.of(linuxWithoutCodex));
        assertThat(service.status().configured()).isFalse();
    }

    @Test
    void localCodexRunnerClaimsAssistantWithCentralAccount() {
        AiRunnerNodeEntity local = runner("Windows Server 2025", "codex,claude");
        AiAssistantRunEntity run = assistantRun("queued");
        AiRunAccountDto account = new AiRunAccountDto(
                "aa1", "codex", "主账号", "oauth", "{}", null, null, null, null);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(aiRunService.authenticateRunner("token")).thenReturn(local);
        when(mapper.selectOne(any())).thenReturn(run);
        when(accountService.defaultCodexRunAccount()).thenReturn(account);

        AiAssistantClaimDto claim = service.claim("token");

        assertThat(claim.runId()).isEqualTo("ac1");
        assertThat(claim.prompt()).isEqualTo("请总结");
        assertThat(claim.account()).isSameAs(account);
        assertThat(run.getStatus()).isEqualTo("running");
        assertThat(run.getRunnerId()).isEqualTo("rn1");
        verify(mapper).updateById(run);
    }

    @Test
    void completionClearsPromptAndStoresBoundedOutcome() {
        AiRunnerNodeEntity linux = runner("Linux 6.8", "codex");
        AiAssistantRunEntity run = assistantRun("running");
        run.setRunnerId("rn1");
        when(aiRunService.authenticateRunner("token")).thenReturn(linux);
        when(mapper.selectById("ac1")).thenReturn(run);

        service.complete("token", "ac1", new CompleteAiAssistantRunRequest(0, "这是答复", null));

        assertThat(run.getStatus()).isEqualTo("succeeded");
        assertThat(run.getPromptText()).isNull();
        assertThat(run.getResponseText()).isEqualTo("这是答复");
        assertThat(run.getFinishedAt()).isNotNull();
        verify(mapper).updateById(run);
        verify(conversationService).appendAssistantReply(run, "这是答复");
    }

    @Test
    void internalReadOnlyJobUsesRunnerQueueWithoutCreatingConversationHistory() {
        AiRunnerNodeEntity local = runner("Linux 6.8", "codex");
        AiAssistantRunEntity completed = assistantRun("succeeded");
        completed.setMemberId("internal-evolution-suggestion");
        completed.setResponseText("{\"suggestions\":[]}");
        when(runnerMapper.selectList(any())).thenReturn(List.of(local));
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectById(any())).thenReturn(completed);

        String response = service.runReadOnlyJob("evolution-suggestions", "只返回 JSON");

        assertThat(response).isEqualTo("{\"suggestions\":[]}");
        ArgumentCaptor<AiAssistantRunEntity> queued = ArgumentCaptor.forClass(AiAssistantRunEntity.class);
        verify(mapper).insert(queued.capture());
        assertThat(queued.getValue().getMemberId()).isEqualTo("internal-evolution-suggestion");
        assertThat(queued.getValue().getPromptText()).contains("只读分析").contains("只返回 JSON");
        verify(conversationService, org.mockito.Mockito.never())
                .enqueue(any(), any(), any(), any());
    }

    @Test
    void dailyGuidanceRunDoesNotCreatePrivateConversation() {
        AiRunnerNodeEntity local = runner("Windows Server 2025", "codex");
        when(runnerMapper.selectList(any())).thenReturn(List.of(local));
        when(mapper.selectCount(any())).thenReturn(0L);
        AiAssistantRunEntity finished = assistantRun("succeeded");
        finished.setMemberId("internal-daily-guidance-wg");
        finished.setResponseText("保持开放，先认识团队");
        when(mapper.selectById(any())).thenReturn(finished);

        assertThat(service.dailyGuidanceAnswer("生成建议", "wg", "真实任务上下文"))
                .contains("先认识团队");

        ArgumentCaptor<AiAssistantRunEntity> queued = ArgumentCaptor.forClass(AiAssistantRunEntity.class);
        verify(mapper).insert(queued.capture());
        assertThat(queued.getValue().getMemberId()).isEqualTo("internal-daily-guidance-wg");
        assertThat(queued.getValue().getPromptText()).contains("生成建议").contains("真实任务上下文");
        verify(conversationService, org.mockito.Mockito.never()).create(any());
        verify(conversationService, org.mockito.Mockito.never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void tianrenPromptSeparatesCastFactsTraditionalSummaryAndAiExtension() {
        AssistantChatRequest request = new AssistantChatRequest(
                "请结合最近的疲惫给我今天的提示", List.of(), null, "cv1", null);

        String prompt = service.buildPrompt(request, List.of(), null,
                "<authoritative_tianren_cast>第 11 卦「泰」</authoritative_tianren_cast>");

        assertThat(prompt)
                .contains("卦象事实", "《易经》卦意（AI 概括）", "当日状态观察（AI 延伸）")
                .contains("《黄帝内经》生活观（AI 概括）", "今日调养提示（AI 延伸）")
                .contains("只有确实掌握原句和具体篇名时才可短句引用并标明出处")
                .contains("不是医疗诊断或治疗方案", "持续不适应咨询合格医务人员")
                .contains("第 11 卦「泰」", "最近的疲惫");
    }

    private AiRunnerNodeEntity runner(String platform, String engines) {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn1");
        runner.setName("本地节点");
        runner.setPlatform(platform);
        runner.setEngines(engines);
        runner.setLastSeenAt(LocalDateTime.now());
        return runner;
    }

    private AiAssistantRunEntity assistantRun(String status) {
        AiAssistantRunEntity run = new AiAssistantRunEntity();
        run.setId("ac1");
        run.setMemberId("wg");
        run.setStatus(status);
        run.setPromptText("请总结");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        return run;
    }
}
