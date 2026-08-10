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
import cn.wayuwa.wao.dto.CompleteAiAssistantRunRequest;
import cn.wayuwa.wao.mapper.AiAssistantRunMapper;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import org.junit.jupiter.api.Test;

class AiAssistantServiceTest {

    private final AiAssistantRunMapper mapper = mock(AiAssistantRunMapper.class);
    private final AiRunnerNodeMapper runnerMapper = mock(AiRunnerNodeMapper.class);
    private final AiRunService aiRunService = mock(AiRunService.class);
    private final AiAccountService accountService = mock(AiAccountService.class);
    private final AiAssistantService service = new AiAssistantService(
            mapper, runnerMapper, aiRunService, accountService);

    @Test
    void statusRequiresOnlineLinuxCodexRunner() {
        AiRunnerNodeEntity windows = runner("Windows Server 2025", "codex");
        AiRunnerNodeEntity linuxWithoutCodex = runner("Linux 6.8", "claude");
        when(runnerMapper.selectList(any())).thenReturn(List.of(windows, linuxWithoutCodex));

        assertThat(service.status().configured()).isFalse();

        when(runnerMapper.selectList(any())).thenReturn(List.of(runner("Linux 6.8", "codex,claude")));
        assertThat(service.status().configured()).isTrue();
    }

    @Test
    void linuxRunnerClaimsAssistantWithCentralAccount() {
        AiRunnerNodeEntity linux = runner("Linux 6.8", "codex,claude");
        AiAssistantRunEntity run = assistantRun("queued");
        AiRunAccountDto account = new AiRunAccountDto(
                "aa1", "codex", "主账号", "oauth", "{}", null, null, null, null);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(aiRunService.authenticateRunner("token")).thenReturn(linux);
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
    }

    private AiRunnerNodeEntity runner(String platform, String engines) {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn1");
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
