package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.domain.CodexRunnerQuotaEntity;
import cn.wayuwa.wao.dto.CodexQuotaCreditsDto;
import cn.wayuwa.wao.dto.CodexQuotaLimitDto;
import cn.wayuwa.wao.dto.CodexQuotaWindowDto;
import cn.wayuwa.wao.dto.RunnerCodexQuotaReport;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import cn.wayuwa.wao.mapper.CodexRunnerQuotaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodexQuotaServiceTest {

    private final CodexRunnerQuotaMapper quotaMapper = mock(CodexRunnerQuotaMapper.class);
    private final AiRunnerNodeMapper runnerMapper = mock(AiRunnerNodeMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final CodexQuotaService service = new CodexQuotaService(quotaMapper, runnerMapper, json);

    @Test
    void heartbeatStoresOnlySanitizedQuotaInsteadOfCredentials() {
        RunnerCodexQuotaReport report = report(" WAGE@example.com ", 62);

        service.report("rn1", report, null, LocalDateTime.of(2026, 8, 18, 11, 0));

        ArgumentCaptor<CodexRunnerQuotaEntity> saved = ArgumentCaptor.forClass(CodexRunnerQuotaEntity.class);
        verify(quotaMapper).insert(saved.capture());
        assertThat(saved.getValue().getAccountEmail()).isEqualTo("wage@example.com");
        assertThat(saved.getValue().getSnapshotJson()).contains("usedPercent").doesNotContain("token");
        assertThat(saved.getValue().getLastSuccessAt()).isNotNull();
    }

    @Test
    void sameAccountOnTwoComputersIsMergedUsingNewestRealSnapshot() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        CodexRunnerQuotaEntity older = entity("rn-win", now.minusMinutes(4), report("wage@example.com", 60));
        CodexRunnerQuotaEntity latest = entity("rn-linux", now.minusMinutes(1), report("WAGE@example.com", 62));
        when(quotaMapper.selectList(null)).thenReturn(List.of(older, latest));
        when(runnerMapper.selectList(null)).thenReturn(List.of(
                runner("rn-win", "Windows 工作电脑", now.minusSeconds(30)),
                runner("rn-linux", "Linux 主节点", now.minusSeconds(20))));

        var accounts = service.listAccounts();

        assertThat(accounts).singleElement().satisfies(account -> {
            assertThat(account.accountEmail()).isEqualTo("wage@example.com");
            assertThat(account.runnerNames()).containsExactly("Linux 主节点", "Windows 工作电脑");
            assertThat(account.limits().get(0).windows().get(0).usedPercent()).isEqualTo(62);
            assertThat(account.fresh()).isTrue();
        });
    }

    @Test
    void failedRefreshKeepsLastSuccessfulSnapshot() {
        CodexRunnerQuotaEntity existing = new CodexRunnerQuotaEntity();
        existing.setRunnerId("rn1");
        existing.setSnapshotJson("{\"limits\":[]}");
        existing.setLastSuccessAt(LocalDateTime.of(2026, 8, 18, 10, 0));
        when(quotaMapper.selectById("rn1")).thenReturn(existing);

        service.report("rn1", null, "Codex 本地接口暂时不可用", LocalDateTime.of(2026, 8, 18, 11, 0));

        assertThat(existing.getSnapshotJson()).isEqualTo("{\"limits\":[]}");
        assertThat(existing.getLastSuccessAt()).isEqualTo(LocalDateTime.of(2026, 8, 18, 10, 0));
        assertThat(existing.getErrorMessage()).contains("暂时不可用");
        verify(quotaMapper).updateById(existing);
    }

    private RunnerCodexQuotaReport report(String email, int usedPercent) {
        return new RunnerCodexQuotaReport(email, "pro", new CodexQuotaCreditsDto(false, false, "0"),
                List.of(new CodexQuotaLimitDto("codex", "通用使用限额",
                        List.of(new CodexQuotaWindowDto(usedPercent, 10_080L, 1_787_201_290L)))));
    }

    private CodexRunnerQuotaEntity entity(String runnerId, LocalDateTime at, RunnerCodexQuotaReport report)
            throws Exception {
        CodexRunnerQuotaEntity entity = new CodexRunnerQuotaEntity();
        entity.setRunnerId(runnerId);
        entity.setAccountEmail(report.accountEmail().toLowerCase());
        entity.setPlan(report.plan());
        entity.setSnapshotJson(json.writeValueAsString(report));
        entity.setLastSuccessAt(at);
        entity.setLastCheckedAt(at);
        return entity;
    }

    private AiRunnerNodeEntity runner(String id, String name, LocalDateTime seenAt) {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId(id);
        runner.setName(name);
        runner.setLastSeenAt(seenAt);
        return runner;
    }
}
